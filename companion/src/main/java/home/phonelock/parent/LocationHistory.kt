package home.phonelock.parent

import android.content.Context
import android.location.Geocoder
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * One entry of a device's location log: it was around ([lat], [lng]) from [from] to [to]. [battery] is the
 * level at the last reading (-1 if unknown).
 */
data class Place(
    val from: Long,
    val to: Long,
    val lat: Double,
    val lng: Double,
    val accuracyM: Int = 0,
    val battery: Int = -1,
    val charging: Boolean = false,
) {
    /** Rounded position (about 10 m), used to share one address lookup between nearby entries. */
    val spot get() = String.format(Locale.US, "%.4f,%.4f", lat, lng)
}

/** A stretch of using one app ([label]) on the device, from [from] to [to]. */
data class AppSession(val app: String, val label: String, val from: Long, val to: Long)

/**
 * The parents' copy of each device's location log (see PROTOCOL.md, `track`). The device keeps 7 days and
 * sends new readings as it takes them; this keeps the same 7 days on the parent's phone, merged by start
 * time, and asks the device for anything newer when the history page opens (covering a parent phone that
 * was off). Addresses are looked up only for rows on screen, and cached in memory.
 */
object LocationHistory {
    private const val KEEP_MS = 7 * 24 * 3600_000L

    private lateinit var app: Context
    private val file by lazy { File(app.filesDir, "location-history.json") }

    private val _places = MutableStateFlow<Map<String, List<Place>>>(emptyMap())
    /** Device -> entries, newest first. */
    val places: StateFlow<Map<String, List<Place>>> = _places.asStateFlow()

    private val appsFile by lazy { File(app.filesDir, "app-history.json") }
    private val _apps = MutableStateFlow<Map<String, List<AppSession>>>(emptyMap())
    /** Device -> app-use sessions, oldest first (sent with the location readings). */
    val apps: StateFlow<Map<String, List<AppSession>>> = _apps.asStateFlow()

    private val _addresses = MutableStateFlow<Map<String, String>>(emptyMap())
    /** [Place.spot] -> street address, for rows already looked up. */
    val addresses: StateFlow<Map<String, String>> = _addresses.asStateFlow()
    private val lookingUp = HashSet<String>()

    fun init(c: Context) {
        app = c.applicationContext
        // Each file on its own: a missing one mustn't stop the other loading. Anything past 7 days is
        // dropped on load too, so a device that went quiet doesn't keep old data on show.
        val cutoff = System.currentTimeMillis() - KEEP_MS
        runCatching {
            if (!file.isFile) return@runCatching
            val o = JSONObject(file.readText())
            _places.value = o.keys().asSequence().associateWith { k -> parse(o.getJSONArray(k)).filter { it.to >= cutoff } }
        }.onFailure { Log.w(TAG, "history load: $it") }
        runCatching {
            if (!appsFile.isFile) return@runCatching
            val o = JSONObject(appsFile.readText())
            _apps.value = o.keys().asSequence().associateWith { k -> parseApps(o.getJSONArray(k)).filter { it.to >= cutoff } }
        }.onFailure { Log.w(TAG, "app history load: $it") }
    }

    /** Asks [dev] for entries since the newest one held here (or the full 7 days). */
    fun requestNewer(dev: String) {
        val newest = _places.value[dev]?.firstOrNull()?.from ?: 0L
        Hub.send(dev, Hub.msg("track").put("since", newest))
    }

    /** A `track` message from [dev]: new or extended entries (it may also be just a heartbeat). */
    fun handle(dev: String, m: JSONObject) {
        m.optJSONArray("apps")?.let { mergeApps(dev, parseApps(it)) }
        val incoming = parse(m.optJSONArray("points") ?: return)
        if (incoming.isEmpty()) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        _places.update { all ->
            // Same start time = same entry, possibly extended since: keep the longer one.
            val byStart = (all[dev].orEmpty() + incoming)
                .groupBy { it.from }
                .mapValues { (_, same) -> same.maxBy { it.to } }
            all + (dev to byStart.values.filter { it.to >= cutoff }.sortedByDescending { it.from })
        }
        save()
    }

    fun forget(dev: String) {
        _places.update { it - dev }
        _apps.update { it - dev }
        save()
        saveApps()
    }

    /** Adds app sessions for [dev]; the same app starting at the same time is the same session. */
    private fun mergeApps(dev: String, incoming: List<AppSession>) {
        if (incoming.isEmpty()) return
        val cutoff = System.currentTimeMillis() - KEEP_MS
        _apps.update { all ->
            val merged = (all[dev].orEmpty() + incoming).groupBy { it.app to it.from }.mapValues { (_, s) -> s.maxBy { it.to } }
            all + (dev to merged.values.filter { it.to >= cutoff }.sortedBy { it.from })
        }
        saveApps()
    }

    private fun parseApps(a: JSONArray): List<AppSession> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        val pkg = o.optString("app").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        AppSession(pkg, o.optString("label", pkg), o.optLong("t"), o.optLong("to"))
    }

    @Synchronized
    private fun saveApps() {
        val o = JSONObject()
        _apps.value.forEach { (dev, list) ->
            val a = JSONArray()
            list.forEach { a.put(JSONObject().put("app", it.app).put("label", it.label).put("t", it.from).put("to", it.to)) }
            o.put(dev, a)
        }
        runCatching {
            val tmp = File(app.filesDir, "app-history.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(appsFile)
        }.onFailure { Log.w(TAG, "app history save: $it") }
    }

    /** Looks up the address for [p] once, in the background; the result lands in [addresses]. */
    fun lookUp(p: Place) {
        val spot = p.spot
        synchronized(lookingUp) {
            if (spot in _addresses.value || !lookingUp.add(spot)) return
        }
        if (!Geocoder.isPresent()) return
        try {
            Geocoder(app).getFromLocation(p.lat, p.lng, 1, object : Geocoder.GeocodeListener {
                override fun onGeocode(results: MutableList<android.location.Address>) {
                    val line = results.firstOrNull()?.getAddressLine(0)
                    if (line != null) _addresses.update { it + (spot to line) }
                    else synchronized(lookingUp) { lookingUp.remove(spot) } // try again next time it's shown
                }
                override fun onError(errorMessage: String?) {
                    synchronized(lookingUp) { lookingUp.remove(spot) }
                }
            })
        } catch (e: Exception) {
            Log.w(TAG, "geocode: $e")
            synchronized(lookingUp) { lookingUp.remove(spot) }
        }
    }

    private fun parse(a: JSONArray): List<Place> = (0 until a.length()).mapNotNull { i ->
        val o = a.optJSONObject(i) ?: return@mapNotNull null
        if (!o.has("lat") || !o.has("lng") || !o.has("t")) return@mapNotNull null
        val from = o.getLong("t")
        Place(from, o.optLong("to", from), o.getDouble("lat"), o.getDouble("lng"), o.optInt("acc"),
            o.optInt("batt", -1), o.optBoolean("chg"))
    }

    @Synchronized
    private fun save() {
        val o = JSONObject()
        _places.value.forEach { (dev, list) ->
            val a = JSONArray()
            list.forEach {
                a.put(JSONObject().put("t", it.from).put("to", it.to).put("lat", it.lat).put("lng", it.lng)
                    .put("acc", it.accuracyM).put("batt", it.battery).put("chg", it.charging))
            }
            o.put(dev, a)
        }
        runCatching {
            val tmp = File(app.filesDir, "location-history.json.tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "history save: $it") }
    }
}
