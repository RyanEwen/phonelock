package home.phonelock.parent

import android.content.Context
import android.graphics.BitmapFactory
import android.location.Geocoder
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONObject
import kotlin.concurrent.thread

/** Reply to "find": a position (when [lat] is set) and/or an [error], plus battery. */
data class PhoneLocation(
    val ts: Long,
    val lat: Double? = null,
    val lng: Double? = null,
    val accuracyM: Long? = null,
    val fixTime: Long? = null,
    val battery: Int? = null,
    val charging: Boolean = false,
    val error: String? = null,
    /** Street address from the system geocoder, filled in shortly after the fix arrives (may stay null). */
    val address: String? = null,
)

/** Reply to "view screen": the decrypted image, or an [error]. [label] names the app that was on screen. */
data class PhoneScreen(val ts: Long, val image: ImageBitmap? = null, val label: String? = null, val error: String? = null)

/**
 * Find, ring and view-screen for paired phones (see PROTOCOL.md). Replies are matched to requests this
 * app sent, so one parent's auto-refreshing screenshots don't download on the other parent's phone.
 * Screenshots stay in memory only and are never written to storage.
 */
object PhoneTools {
    private val _locations = MutableStateFlow<Map<String, PhoneLocation>>(emptyMap())
    val locations: StateFlow<Map<String, PhoneLocation>> = _locations.asStateFlow()
    private val _screens = MutableStateFlow<Map<String, PhoneScreen>>(emptyMap())
    val screens: StateFlow<Map<String, PhoneScreen>> = _screens.asStateFlow()

    /** Devices with a find / screen request in flight, for progress indicators. */
    val locating = MutableStateFlow<Set<String>>(emptySet())
    val capturing = MutableStateFlow<Set<String>>(emptySet())

    private lateinit var app: Context

    fun init(c: Context) {
        app = c.applicationContext
    }

    /** Request id -> device, for requests sent from this app. */
    private val myRequests = LinkedHashMap<String, String>()

    /** Gives up on the progress indicator if the phone doesn't answer (offline, asleep). */
    private const val REPLY_TIMEOUT_MS = 60_000L
    private val main = Handler(Looper.getMainLooper())
    private val timeoutTokens = HashMap<String, Any>()

    fun find(dev: String) = request(dev, "locate", locating)

    /**
     * Asks [dev] for a screenshot. The phone always shows the child a "viewed your screen" notice and the
     * parents' activity log records the view.
     */
    fun viewScreen(dev: String) = request(dev, "screen", capturing)

    private fun request(dev: String, type: String, inFlight: MutableStateFlow<Set<String>>,
                        extra: (JSONObject) -> Unit = {}) {
        inFlight.update { it + dev }
        Hub.send(dev, track(dev, Hub.msg(type).also(extra)))
        // Handler matches tokens by identity, so reuse one token object per (kind, device).
        val token = synchronized(timeoutTokens) { timeoutTokens.getOrPut("$type/$dev") { Any() } }
        main.removeCallbacksAndMessages(token)
        main.postDelayed({ inFlight.update { it - dev } }, token, REPLY_TIMEOUT_MS)
    }

    fun ring(dev: String, seconds: Int = 120) = Hub.send(dev, Hub.msg("ring").put("op", "start").put("seconds", seconds))

    fun stopRinging(dev: String) = Hub.send(dev, Hub.msg("ring").put("op", "stop"))

    /** Forgets the screenshot shown for [dev]. */
    fun clearScreen(dev: String) = _screens.update { it - dev }

    /** Drops what's held in memory for [dev] (the device was removed). */
    fun forget(dev: String) {
        _locations.update { it - dev }
        _screens.update { it - dev }
        locating.update { it - dev }
        capturing.update { it - dev }
    }

    private fun track(dev: String, o: JSONObject): JSONObject {
        synchronized(myRequests) {
            myRequests[o.getString("id")] = dev
            while (myRequests.size > 100) myRequests.remove(myRequests.keys.first())
        }
        return o
    }

    private fun isMine(req: String) = synchronized(myRequests) { myRequests.containsKey(req) }

    /** A `loc` or `shot` message from [d]. [attachmentUrl] carries the encrypted screenshot, if any. */
    fun handle(d: Device, m: JSONObject, attachmentUrl: String?) {
        if (!isMine(m.optString("req"))) return
        when (m.optString("t")) {
            "loc" -> onLocation(d.id, m)
            "shot" -> onScreen(d, m, attachmentUrl)
        }
    }

    private fun onLocation(dev: String, m: JSONObject) {
        locating.update { it - dev }
        val loc = PhoneLocation(
            ts = m.optLong("ts"),
            lat = if (m.has("lat")) m.getDouble("lat") else null,
            lng = if (m.has("lng")) m.getDouble("lng") else null,
            accuracyM = if (m.has("acc")) m.getLong("acc") else null,
            fixTime = if (m.has("fix")) m.getLong("fix") else null,
            battery = if (m.has("batt")) m.getInt("batt") else null,
            charging = m.optBoolean("chg"),
            error = m.optString("error").takeIf { it.isNotEmpty() },
        )
        _locations.update { it + (dev to loc) }
        if (loc.lat != null && loc.lng != null) lookUpAddress(dev, loc)
    }

    /**
     * Turns the fix into a street address with Android's geocoder (Google Play services on the parent's
     * phone). Best effort: no geocoder, no network or no result just leaves the address empty.
     */
    private fun lookUpAddress(dev: String, loc: PhoneLocation) {
        if (!Geocoder.isPresent()) return
        try {
            Geocoder(app).getFromLocation(loc.lat!!, loc.lng!!, 1) { results ->
                val line = results.firstOrNull()?.getAddressLine(0) ?: return@getFromLocation
                // Only fill in the fix it belongs to; a newer "Location" tap may have replaced it.
                _locations.update { m ->
                    val cur = m[dev]
                    if (cur?.ts == loc.ts) m + (dev to cur.copy(address = line)) else m
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "geocode: $e")
        }
    }

    private fun onScreen(d: Device, m: JSONObject, attachmentUrl: String?) {
        val ts = m.optLong("ts")
        val label = m.optString("label").takeIf { it.isNotEmpty() }
        val error = m.optString("error").takeIf { it.isNotEmpty() }
        if (error != null || attachmentUrl == null) {
            capturing.update { it - d.id }
            setScreen(d.id, PhoneScreen(ts, label = label, error = error ?: "No image received"))
            return
        }
        thread(name = "shot") {
            val image = runCatching { download(d, attachmentUrl, m.getString("id")) }
                .onFailure { Log.w(TAG, "shot download: $it") }
                .getOrNull()
            capturing.update { it - d.id }
            setScreen(d.id, if (image != null) PhoneScreen(ts, image, label)
                else PhoneScreen(ts, label = label, error = "Couldn't open the screenshot"))
        }
    }

    /** Keeps the newest screenshot, since replies can arrive out of order during auto-refresh. */
    private fun setScreen(dev: String, s: PhoneScreen) =
        _screens.update { cur -> if ((cur[dev]?.ts ?: 0) > s.ts) cur else cur + (dev to s) }

    /** Fetches and decrypts the screenshot attached to sealed message [msgId]. */
    private fun download(d: Device, url: String, msgId: String): ImageBitmap? {
        val jpeg = Attachments.fetch(d, url, msgId) ?: return null
        return BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)?.asImageBitmap()
    }
}
