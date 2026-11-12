package home.phonelock.parent

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * A daily time limit on one app. [minutes] has one entry per day of the week, Sunday first; -1 means no
 * limit that day. On the wire (PROTOCOL.md `limits`) a limit carries a list of apps and a name, which the
 * device can treat as a shared group; this app only ever sends one app and no name, and ignores any
 * multi-app limit a device reports (the next edit drops it).
 */
data class TimeLimit(val id: String, val app: String, val minutes: List<Int>) {
    fun toJson(): JSONObject = JSONObject().put("id", id).put("name", "")
        .put("apps", JSONArray(listOf(app))).put("min", JSONArray(minutes))

    companion object {
        /** The limit in [o], or null when it isn't for exactly one app. */
        fun from(o: JSONObject): TimeLimit? {
            val a = o.optJSONArray("apps")
            if (a == null || a.length() != 1) return null
            val m = o.optJSONArray("min")
            return TimeLimit(o.optString("id"), a.getString(0), (0 until 7).map { m?.optInt(it, -1) ?: -1 })
        }

        fun newId(): String = UUID.randomUUID().toString().take(8)
    }
}

/**
 * Today on the device, as of [ts]: minutes per app ([apps]), minutes counted towards each limit ([used], by
 * limit id: time allowed by a grant or in parent mode isn't counted), and the limits that have run out ([out]).
 */
data class AppTimeToday(val ts: Long, val day: String, val apps: Map<String, Int>, val used: Map<String, Int>,
                        val out: Set<String>, val labels: Map<String, String>)

/**
 * The parent side of daily app time limits and app-use reports (PROTOCOL.md: `limits`, `usage`).
 *
 * The device enforces the limits and keeps the record; this keeps each device's limits and today's use from
 * its state messages, sends edits, and keeps a copy of the daily record for reports, asking the device only
 * for days it doesn't have yet. Stored in this app's private files; removing a device forgets it.
 */
object AppTime {
    private const val TAG = "PhoneLock"

    private lateinit var app: Context
    private val file by lazy { File(app.filesDir, "app-time.json") }

    private val _limits = MutableStateFlow<Map<String, List<TimeLimit>>>(emptyMap())
    /** Device -> its limits, as last reported (or as just edited here). */
    val limits: StateFlow<Map<String, List<TimeLimit>>> = _limits.asStateFlow()

    private val _today = MutableStateFlow<Map<String, AppTimeToday>>(emptyMap())
    val today: StateFlow<Map<String, AppTimeToday>> = _today.asStateFlow()

    private val _days = MutableStateFlow<Map<String, Map<String, Map<String, Int>>>>(emptyMap())
    /** Device -> day ("yyyy-MM-dd") -> app -> minutes. */
    val days: StateFlow<Map<String, Map<String, Map<String, Int>>>> = _days.asStateFlow()

    private val _labels = MutableStateFlow<Map<String, Map<String, String>>>(emptyMap())
    /** Device -> app names seen in its reports (apps since removed keep their name here). */
    val labels: StateFlow<Map<String, Map<String, String>>> = _labels.asStateFlow()

    private val _error = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Device -> why its record can't be read (usage access missing). */
    val error: StateFlow<Map<String, String>> = _error.asStateFlow()


    fun init(c: Context) {
        app = c.applicationContext
        runCatching {
            if (!file.isFile) return@runCatching
            val o = JSONObject(file.readText())
            o.optJSONObject("limits")?.let { l ->
                _limits.value = l.keys().asSequence().associateWith { k -> parseLimits(l.getJSONArray(k)) }
            }
            o.optJSONObject("days")?.let { d -> _days.value = d.keys().asSequence().associateWith { k -> parseDays(d.getJSONObject(k)) } }
            o.optJSONObject("labels")?.let { l -> _labels.value = l.keys().asSequence().associateWith { k -> strings(l.getJSONObject(k)) } }
        }.onFailure { Log.w(TAG, "app time load: $it") }
    }

    // ---------- limits ----------

    /** Replaces [dev]'s limits; shown here at once, and confirmed by the device's next state. */
    fun setLimits(dev: String, list: List<TimeLimit>) {
        _limits.update { it + (dev to list) }
        save()
        Hub.send(dev, Hub.msg("limits").put("limits", JSONArray(list.map { it.toJson() })))
    }

    fun saveLimit(dev: String, limit: TimeLimit) {
        val cur = _limits.value[dev].orEmpty()
        setLimits(dev, if (cur.any { it.id == limit.id }) cur.map { if (it.id == limit.id) limit else it } else cur + limit)
    }

    fun removeLimit(dev: String, id: String) = setLimits(dev, _limits.value[dev].orEmpty().filterNot { it.id == id })

    // ---------- incoming ----------

    /**
     * The limit and today's-use parts of a `state` message from [dev] at [ts]. Older states than the one
     * already shown are ignored, like the rest of the state.
     */
    fun handleState(dev: String, m: JSONObject, ts: Long) {
        val cur = _today.value[dev]
        if (cur != null && ts < cur.ts) return
        m.optJSONArray("limits")?.let { a ->
            _limits.update { it + (dev to parseLimits(a)) }
        }
        m.optJSONObject("appTime")?.let { t ->
            _today.update {
                it + (dev to AppTimeToday(ts, t.optString("day"), ints(t.optJSONObject("apps")),
                    ints(t.optJSONObject("used")),
                    t.optJSONArray("out")?.let { a -> (0 until a.length()).map { i -> a.getString(i) }.toSet() } ?: emptySet(),
                    strings(t.optJSONObject("labels"))))
            }
            t.optJSONObject("labels")?.let { l -> mergeLabels(dev, strings(l)) }
        }
        save()
    }

    /** A `usage` message: the device's daily record from the requested day on. */
    fun handleUsage(dev: String, m: JSONObject) {
        val days = m.optJSONObject("days")?.let(::parseDays) ?: emptyMap()
        _days.update { all -> all + (dev to (all[dev].orEmpty() + days)) }
        m.optJSONObject("labels")?.let { mergeLabels(dev, strings(it)) }
        _error.update { e -> m.optString("error").takeIf { it.isNotEmpty() }?.let { e + (dev to it) } ?: (e - dev) }
        save()
    }

    /**
     * Asks [dev] for the days this app doesn't have yet (from the last stored day, which may have grown since,
     * or a year back the first time).
     */
    fun requestHistory(dev: String) {
        val have = _days.value[dev].orEmpty().keys.maxOrNull()
        val since = have ?: LocalDate.now().minusDays(366).toString()
        // "since", not "from": every message's "from" is already the sender's name.
        val m = Hub.msg("usage").put("since", since)
        m.put("req", m.getString("id"))
        // Tracked as "usage" so the report can show it's waiting (and give up after a minute if offline).
        Pending.send(dev, "usage", m)
    }

    /** Drops everything kept about [dev] (the device was removed). */
    fun forget(dev: String) {
        _limits.update { it - dev }
        _today.update { it - dev }
        _days.update { it - dev }
        _labels.update { it - dev }
        _error.update { it - dev }
        save()
    }

    // ---------- storage ----------

    @Synchronized
    private fun save() {
        if (!::app.isInitialized) return
        runCatching {
            val o = JSONObject()
            o.put("limits", JSONObject().also { l -> _limits.value.forEach { (k, v) -> l.put(k, JSONArray(v.map { it.toJson() })) } })
            o.put("days", JSONObject().also { d ->
                _days.value.forEach { (k, v) ->
                    d.put(k, JSONObject().also { dd -> v.forEach { (day, apps) -> dd.put(day, JSONObject(apps as Map<*, *>)) } })
                }
            })
            o.put("labels", JSONObject().also { l -> _labels.value.forEach { (k, v) -> l.put(k, JSONObject(v as Map<*, *>)) } })
            val tmp = File(file.path + ".tmp")
            tmp.writeText(o.toString())
            tmp.renameTo(file)
        }.onFailure { Log.w(TAG, "app time save: $it") }
    }

    private fun mergeLabels(dev: String, l: Map<String, String>) {
        _labels.update { all -> all + (dev to (all[dev].orEmpty() + l)) }
    }

    private fun parseLimits(a: JSONArray) = (0 until a.length()).mapNotNull { TimeLimit.from(a.getJSONObject(it)) }

    private fun parseDays(o: JSONObject): Map<String, Map<String, Int>> =
        o.keys().asSequence().associateWith { ints(o.optJSONObject(it)) }

    private fun ints(o: JSONObject?): Map<String, Int> =
        o?.keys()?.asSequence()?.associateWith { o.optInt(it) } ?: emptyMap()

    private fun strings(o: JSONObject?): Map<String, String> =
        o?.keys()?.asSequence()?.associateWith { o.optString(it) } ?: emptyMap()
}
