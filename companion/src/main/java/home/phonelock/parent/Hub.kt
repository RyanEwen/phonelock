package home.phonelock.parent

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

const val TAG = "PhoneLockParent"

/** One locked phone. [id] is its ntfy topic. */
data class Device(val server: String, val topic: String, val secret: String, val name: String) {
    val id get() = topic
}

data class PhoneState(
    val updated: Long = 0,
    val name: String = "",
    val grants: Map<String, Long> = emptyMap(),
    val home: List<String> = emptyList(),
    val others: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val parentMode: Boolean = false,
    val ringing: Boolean = false,
    /** Apps a parent took off the "Give access" list; shown so they can be put back. */
    val notOffered: List<String> = emptyList(),
    /** Current situation ("normal", "limited", "bedtime") and when it ends (0 = no schedule). */
    val mode: String = "normal",
    val modeUntil: Long = 0,
    /** Situations each scheduled app is allowed in (bits: 1 normal, 2 limited hours, 4 bedtime). */
    val masks: Map<String, Int> = emptyMap(),
    val schedule: List<ScheduleRule> = emptyList(),
    /** True once a full state (with app lists, table and schedule) has arrived; editing waits for it. */
    val full: Boolean = false,
) {
    fun label(pkg: String) = labels[pkg] ?: pkg
}

data class Request(val device: String, val req: String, val app: String, val label: String,
                   val minutes: Long, val ts: Long)
data class Event(val ts: Long, val text: String)

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        AppIconCache.init(this)
        PhoneTools.init(this)
        Push.init(this)
        Push.sync(Hub.devices.value)
    }
}

/** Process-wide state shared by the UI, the listener service and notification actions. */
object Hub {
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("hub", Context.MODE_PRIVATE) }

    private val _devices = MutableStateFlow<List<Device>>(emptyList())
    val devices: StateFlow<List<Device>> = _devices.asStateFlow()
    private val _parentName = MutableStateFlow("")
    val parentName: StateFlow<String> = _parentName.asStateFlow()
    private val _states = MutableStateFlow<Map<String, PhoneState>>(emptyMap())
    val states: StateFlow<Map<String, PhoneState>> = _states.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val _events = MutableStateFlow<Map<String, List<Event>>>(emptyMap())
    val events: StateFlow<Map<String, List<Event>>> = _events.asStateFlow()
    val connected = MutableStateFlow(false)

    private val sentIds = ArrayDeque<String>()
    /** Seen message ids with their timestamps, persisted; kept for [MAX_AGE_MS]. */
    private val seenIds = LinkedHashMap<String, Long>()
    private const val MAX_AGE_MS = 12 * 3600_000L

    /** Records [id]; false if it was already seen. */
    private fun markSeen(id: String, ts: Long): Boolean = synchronized(seenIds) {
        if (id.isEmpty() || seenIds.containsKey(id)) return false
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        seenIds.entries.removeAll { it.value < cutoff }
        seenIds[id] = ts
        while (seenIds.size > 5000) seenIds.remove(seenIds.keys.first())
        prefs.edit().putString("seen_ids", JSONObject(seenIds as Map<*, *>).toString()).apply()
        true
    }

    fun init(c: Context) {
        app = c.applicationContext
        _parentName.value = prefs.getString("parent_name", "") ?: ""
        prefs.getString("seen_ids", null)?.let { j ->
            runCatching { JSONObject(j).let { o -> o.keys().forEach { seenIds[it] = o.getLong(it) } } }
        }
        prefs.getString("devices", null)?.let { s ->
            val a = JSONArray(s)
            _devices.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                Device(it.getString("server"), it.getString("topic"), it.getString("secret"), it.getString("name"))
            }
        }
        // Migrate the single-phone config from version 1.
        prefs.getString("config", null)?.let { s ->
            val o = JSONObject(s)
            if (_parentName.value.isEmpty()) setParentName(o.optString("name"))
            addDevice(Device(o.getString("server"), o.getString("topic"), o.getString("secret"), "Device"))
            prefs.edit().remove("config").remove("state").remove("requests").remove("events").apply()
        }
        prefs.getString("states", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                _states.value = o.keys().asSequence().associateWith { parseState(o.getJSONObject(it)) }
            }
        }
        prefs.getString("requests", null)?.let { s ->
            runCatching {
                val a = JSONArray(s)
                _requests.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                    Request(it.getString("device"), it.getString("req"), it.getString("app"),
                        it.optString("label"), it.optLong("minutes", 60), it.optLong("ts"))
                }.filter { System.currentTimeMillis() - it.ts < 12 * 3600_000L }
            }
        }
        prefs.getString("events", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                _events.value = o.keys().asSequence().associateWith { k ->
                    val a = o.getJSONArray(k)
                    (0 until a.length()).map { a.getJSONObject(it) }.map { Event(it.getLong("ts"), it.getString("text")) }
                }
            }
        }
    }

    fun deviceName(id: String): String =
        _states.value[id]?.name?.takeIf { it.isNotBlank() && it != "Phone" && it != "Device" }
            ?: _devices.value.firstOrNull { it.id == id }?.name ?: "Device"

    // ---------- pairing ----------

    /** phonelock://pair?s=<server>&t=<topic>&k=<secret>&n=<name> */
    fun parsePairing(link: String): Device? = runCatching {
        val u = Uri.parse(link.trim())
        if (u.scheme != "phonelock" || u.host != "pair") return null
        val k = u.getQueryParameter("k")!!
        Crypto.key(k)
        Device((u.getQueryParameter("s") ?: "https://ntfy.sh").trimEnd('/'), u.getQueryParameter("t")!!, k,
            u.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "Device")
    }.getOrNull()

    fun setParentName(name: String) {
        // "phone" is how the locked phone signs its own messages; it ignores commands from that name.
        val n = name.trim().let { if (it.equals("phone", ignoreCase = true)) "Parent" else it }
        _parentName.value = n
        prefs.edit().putString("parent_name", n).apply()
    }

    fun addDevice(d: Device) {
        _devices.update { l -> l.filterNot { it.id == d.id } + d }
        saveDevices()
        Push.sync(listOf(d))
        // Fetch its current state now rather than waiting for the next app resume.
        refresh(d.id)
        ParentService.restart(app)
    }

    fun renameDevice(id: String, name: String) {
        _devices.update { l -> l.map { if (it.id == id) it.copy(name = name.trim()) else it } }
        saveDevices()
    }

    fun removeDevice(id: String) {
        _devices.value.firstOrNull { it.id == id }?.let { Push.sync(emptyList(), removed = listOf(it)) }
        _devices.update { l -> l.filterNot { it.id == id } }
        _states.update { it - id }
        _events.update { it - id }
        _requests.update { l -> l.filterNot { it.device == id } }
        saveDevices(); saveStates(); saveEvents(); saveRequests()
        if (_devices.value.isEmpty()) ParentService.stop(app) else ParentService.restart(app)
    }

    private fun saveDevices() {
        val a = JSONArray()
        _devices.value.forEach {
            a.put(JSONObject().put("server", it.server).put("topic", it.topic).put("secret", it.secret).put("name", it.name))
        }
        prefs.edit().putString("devices", a.toString()).apply()
    }

    var lastNtfyTime: Long
        get() = prefs.getLong("ntfy_last_time", 0)
        set(v) { prefs.edit().putLong("ntfy_last_time", v).apply() }

    // ---------- outgoing ----------

    /** A new outgoing message of [type] with the common fields; its id is remembered so echoes are dropped. */
    internal fun msg(type: String): JSONObject {
        val id = Crypto.randomId()
        synchronized(sentIds) { sentIds.addLast(id); while (sentIds.size > 200) sentIds.removeFirst() }
        return JSONObject().put("v", 1).put("t", type).put("id", id)
            .put("ts", System.currentTimeMillis()).put("from", _parentName.value.ifBlank { "Parent" })
    }

    fun refreshAll() = _devices.value.forEach { refresh(it.id) }

    /**
     * Refreshes phones whose state is older than [maxAgeMs]. Used when the app comes to the front, so
     * flipping in and out doesn't spend ntfy.sh's daily message allowance (250 per visitor).
     */
    fun refreshStale(maxAgeMs: Long = 2 * 60_000L) = _devices.value.forEach { d ->
        val s = _states.value[d.id]
        if (s == null || !s.full || System.currentTimeMillis() - s.updated > maxAgeMs) refresh(d.id)
    }
    fun refresh(dev: String, full: Boolean = true) = send(dev, msg("status").put("full", full))

    fun grant(dev: String, app: String, minutes: Long, req: String? = null) {
        send(dev, msg("grant").put("app", app).put("minutes", minutes).apply { req?.let { put("req", it) } })
        req?.let(::resolve)
    }

    fun revoke(dev: String, app: String) = send(dev, msg("revoke").put("app", app))

    fun deny(r: Request) {
        send(r.device, msg("deny").put("req", r.req).put("label", r.label))
        resolve(r.req)
    }

    fun home(dev: String, op: String, app: String) = send(dev, msg("home").put("op", op).put("app", app))

    /** Sets the situations [app] is allowed in ([ScheduleRule] bits); 0 leaves it to temporary grants. */
    fun appMode(dev: String, app: String, mask: Int) = send(dev, msg("appmode").put("app", app).put("mask", mask))

    /** Replaces the phone's weekly schedule. */
    fun setSchedule(dev: String, rules: List<ScheduleRule>) =
        send(dev, msg("schedule").put("rules", ScheduleRule.toJson(rules)))

    /** Puts [app] on ([on]) or takes it off the phone's "Give access" list and the child's Ask picker. */
    fun offer(dev: String, app: String, on: Boolean) = send(dev, msg("offer").put("app", app).put("on", on))

    /** Seals [o] with [dev]'s key and posts it to the phone's topic, logging failures to its activity. */
    internal fun send(dev: String, o: JSONObject) {
        val d = _devices.value.firstOrNull { it.id == dev } ?: return
        thread {
            try {
                val body = Crypto.seal(Crypto.key(d.secret), o.toString())
                val h = URL("${d.server}/${d.topic}").openConnection() as HttpURLConnection
                h.requestMethod = "POST"
                h.doOutput = true
                h.connectTimeout = 15_000
                h.readTimeout = 15_000
                h.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
                val code = h.responseCode
                h.disconnect()
                if (code !in 200..299) addEvent(dev, "Couldn't send (HTTP $code)")
            } catch (e: Exception) {
                addEvent(dev, "Couldn't send: ${e.message}")
            }
        }
    }

    // ---------- incoming ----------

    /** One ntfy message for [topic]. [attachmentUrl] is the ntfy attachment's URL, if it carried one. */
    fun handle(topic: String, body: String, attachmentUrl: String? = null) {
        val d = _devices.value.firstOrNull { it.topic == topic } ?: return
        val json = Crypto.open(Crypto.key(d.secret), body) ?: return
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        dispatch(d, m, attachmentUrl)
    }

    /** Handles one decrypted message from or about [d]; drops echoes of our own and duplicates. */
    private fun dispatch(d: Device, m: JSONObject, attachmentUrl: String?) {
        val id = m.optString("id")
        synchronized(sentIds) { if (id in sentIds) return }
        val from = m.optString("from")
        val ts = m.optLong("ts")
        // ntfy keeps messages 12 h and anyone who knows the topic can re-post captured ones: drop old ones,
        // and remember ids across restarts so a replay isn't treated as new (e.g. an old request).
        if (ts <= 0 || System.currentTimeMillis() - ts > MAX_AGE_MS) return
        if (!markSeen(id, ts)) return
        val dev = d.id
        when (m.optString("t")) {
            "state" -> if (from == "phone") {
                val s = parseState(m)
                val old = _states.value[dev]
                // States can arrive out of order (a full one travels as a slower attachment): keep the newest.
                if (old != null && s.updated < old.updated) return
                val merged = if (s.full || old == null) s
                    else s.copy(others = old.others, notOffered = old.notOffered, labels = old.labels + s.labels,
                        masks = old.masks, schedule = old.schedule, full = old.full)
                _states.update { it + (dev to merged) }
                saveStates()
                AppIconCache.ensure(d, merged.home + merged.others + merged.notOffered + merged.grants.keys)
            }
            "event" -> if (from == "phone") {
                val text = m.optString("text")
                addEvent(dev, text, ts)
                // Who caused it decides whether it's worth a notification: your own actions and routine news
                // (bedtime starting) only go in the activity list; the other parent's are silent.
                val by = m.optString("by").takeIf { it.isNotEmpty() }
                when {
                    by != null && by == _parentName.value -> {}
                    m.optBoolean("quiet") -> {}
                    by != null -> notify(dev, text, null, channel = "others")
                    else -> notify(dev, text, null)
                }
            }
            "req" -> if (from == "phone") {
                val r = Request(dev, m.getString("req"), m.getString("app"), m.optString("label"),
                    m.optLong("minutes", 60), ts)
                if (_requests.value.none { it.req == r.req }) {
                    _requests.update { it + r }
                    saveRequests()
                    addEvent(dev, "Asked for ${r.label} (${fmtMinutes(r.minutes)})", ts)
                    notify(dev, "Asks for ${r.label} for ${fmtMinutes(r.minutes)}", r)
                }
            }
            // A message too big for ntfy's 4 KB limit, carried as an encrypted attachment.
            "big" -> if (from == "phone" && attachmentUrl != null) thread(name = "big") {
                runCatching { Attachments.fetch(d, attachmentUrl, id) }.getOrNull()
                    ?.let { runCatching { JSONObject(String(it)) }.getOrNull() }
                    ?.let { dispatch(d, it, null) }
            }
            "loc", "shot" -> if (from == "phone") PhoneTools.handle(d, m, attachmentUrl)
            "icons" -> if (from == "phone") AppIconCache.handle(d, m, attachmentUrl)
            // Another parent answered a request.
            "grant", "deny" -> m.optString("req").takeIf { it.isNotEmpty() }?.let(::resolve)
        }
    }

    private fun parseState(m: JSONObject): PhoneState {
        val labels = m.optJSONObject("labels")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } }
            ?: emptyMap()
        val grants = m.optJSONObject("grants")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } }
            ?: emptyMap()
        fun arr(n: String) = m.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getString(it) } }
            ?: emptyList()
        return PhoneState(m.optLong("ts"), m.optString("name"), grants, arr("home"), arr("others"), labels,
            m.optBoolean("parent"), m.optBoolean("ringing"), arr("notOffered"),
            m.optString("mode", "normal"), m.optLong("modeUntil"),
            m.optJSONObject("masks")?.let { o -> o.keys().asSequence().associateWith { o.getInt(it) } } ?: emptyMap(),
            ScheduleRule.listFrom(m.optJSONArray("schedule")), m.has("others") || m.optBoolean("full"))
    }

    private fun stateJson(s: PhoneState) = JSONObject().put("ts", s.updated).put("name", s.name)
        .put("grants", JSONObject(s.grants as Map<*, *>)).put("home", JSONArray(s.home))
        .put("others", JSONArray(s.others)).put("labels", JSONObject(s.labels as Map<*, *>))
        .put("parent", s.parentMode).put("ringing", s.ringing)
        .put("notOffered", JSONArray(s.notOffered))
        .put("mode", s.mode).put("modeUntil", s.modeUntil)
        .put("masks", JSONObject(s.masks as Map<*, *>)).put("schedule", ScheduleRule.toJson(s.schedule))
        .put("full", s.full)

    private fun saveStates() {
        val o = JSONObject()
        _states.value.forEach { (k, v) -> o.put(k, stateJson(v)) }
        prefs.edit().putString("states", o.toString()).apply()
    }

    private fun resolve(req: String) {
        _requests.update { l -> l.filterNot { it.req == req } }
        saveRequests()
        app.getSystemService(NotificationManager::class.java).cancel(req.hashCode())
    }

    private fun saveRequests() {
        val a = JSONArray()
        _requests.value.forEach {
            a.put(JSONObject().put("device", it.device).put("req", it.req).put("app", it.app)
                .put("label", it.label).put("minutes", it.minutes).put("ts", it.ts))
        }
        prefs.edit().putString("requests", a.toString()).apply()
    }

    fun addEvent(dev: String, text: String, ts: Long = System.currentTimeMillis()) {
        _events.update { m -> m + (dev to (listOf(Event(ts, text)) + (m[dev] ?: emptyList())).take(50)) }
        saveEvents()
    }

    private fun saveEvents() {
        val o = JSONObject()
        _events.value.forEach { (k, l) ->
            val a = JSONArray()
            l.forEach { a.put(JSONObject().put("ts", it.ts).put("text", it.text)) }
            o.put(k, a)
        }
        prefs.edit().putString("events", o.toString()).apply()
    }

    // ---------- notifications ----------

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("requests", "Requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("events", "Device activity", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("others", "Other parent's actions", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel("service", "Connection", NotificationManager.IMPORTANCE_MIN))
    }

    /** Posts [text] for [dev]; with a request [r] it gets answer buttons. [channel] overrides the default. */
    private fun notify(dev: String, text: String, r: Request?, channel: String? = null) {
        channels(app)
        val open = PendingIntent.getActivity(app, dev.hashCode(),
            Intent(app, MainActivity::class.java).putExtra("device", dev), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(app, channel ?: if (r != null) "requests" else "events")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(deviceName(dev))
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (r != null) {
            fun action(label: String, kind: String, minutes: Long, code: Int) =
                Notification.Action.Builder(null, label, PendingIntent.getBroadcast(app, code,
                    Intent(app, ActionReceiver::class.java).putExtra("kind", kind).putExtra("req", r.req)
                        .putExtra("minutes", minutes),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
            val base = r.req.hashCode()
            b.addAction(action("Allow ${fmtMinutes(r.minutes)}", "allow", r.minutes, base))
            // Android shows at most 3 actions, so other lengths are picked in the app.
            b.addAction(Notification.Action.Builder(null, "Choose time…", open).build())
            b.addAction(action("Deny", "deny", 0, base + 2))
        }
        val nid = r?.req?.hashCode() ?: (System.currentTimeMillis() and 0x7fffffff).toInt()
        try {
            app.getSystemService(NotificationManager::class.java).notify(nid, b.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification permission")
        }
    }

    fun answer(reqId: String, kind: String, minutes: Long) {
        val r = _requests.value.firstOrNull { it.req == reqId } ?: run {
            app.getSystemService(NotificationManager::class.java).cancel(reqId.hashCode()); return
        }
        if (kind == "allow") grant(r.device, r.app, minutes, r.req) else deny(r)
    }
}

/** Grant lengths offered to parents, in minutes. */
val GRANT_DURATIONS = listOf(15L, 30L, 60L, 120L, 240L, 480L)

fun fmtMinutes(m: Long): String = when {
    m % 60 == 0L -> "${m / 60} h"
    m > 60 -> "${m / 60} h ${m % 60} min"
    else -> "$m min"
}
