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
        PhoneTools.init(this)
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
    private val seenIds = ArrayDeque<String>()

    fun init(c: Context) {
        app = c.applicationContext
        _parentName.value = prefs.getString("parent_name", "") ?: ""
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
            addDevice(Device(o.getString("server"), o.getString("topic"), o.getString("secret"), "Phone"))
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
        _states.value[id]?.name?.takeIf { it.isNotBlank() && it != "Phone" }
            ?: _devices.value.firstOrNull { it.id == id }?.name ?: "Phone"

    // ---------- pairing ----------

    /** phonelock://pair?s=<server>&t=<topic>&k=<secret>&n=<name> */
    fun parsePairing(link: String): Device? = runCatching {
        val u = Uri.parse(link.trim())
        if (u.scheme != "phonelock" || u.host != "pair") return null
        val k = u.getQueryParameter("k")!!
        Crypto.key(k)
        Device((u.getQueryParameter("s") ?: "https://ntfy.sh").trimEnd('/'), u.getQueryParameter("t")!!, k,
            u.getQueryParameter("n")?.takeIf { it.isNotBlank() } ?: "Phone")
    }.getOrNull()

    fun setParentName(name: String) {
        _parentName.value = name.trim()
        prefs.edit().putString("parent_name", name.trim()).apply()
    }

    fun addDevice(d: Device) {
        _devices.update { l -> l.filterNot { it.id == d.id } + d }
        saveDevices()
        ParentService.restart(app)
    }

    fun renameDevice(id: String, name: String) {
        _devices.update { l -> l.map { if (it.id == id) it.copy(name = name.trim()) else it } }
        saveDevices()
    }

    fun removeDevice(id: String) {
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
        val id = m.optString("id")
        synchronized(sentIds) { if (id in sentIds) return }
        synchronized(seenIds) {
            if (id in seenIds) return
            seenIds.addLast(id); while (seenIds.size > 500) seenIds.removeFirst()
        }
        val from = m.optString("from")
        val ts = m.optLong("ts")
        val dev = d.id
        when (m.optString("t")) {
            "state" -> if (from == "phone") {
                val s = parseState(m)
                val old = _states.value[dev]
                val merged = if (m.has("others") || old == null) s
                    else s.copy(others = old.others, labels = old.labels + s.labels)
                _states.update { it + (dev to merged) }
                saveStates()
            }
            "event" -> if (from == "phone") {
                val text = m.optString("text")
                addEvent(dev, text, ts)
                if (!text.startsWith("${_parentName.value}:")) notify(dev, text, null)
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
            "loc", "shot" -> if (from == "phone") PhoneTools.handle(d, m, attachmentUrl)
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
            m.optBoolean("parent"), m.optBoolean("ringing"))
    }

    private fun stateJson(s: PhoneState) = JSONObject().put("ts", s.updated).put("name", s.name)
        .put("grants", JSONObject(s.grants as Map<*, *>)).put("home", JSONArray(s.home))
        .put("others", JSONArray(s.others)).put("labels", JSONObject(s.labels as Map<*, *>))
        .put("parent", s.parentMode).put("ringing", s.ringing)

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
        nm.createNotificationChannel(NotificationChannel("events", "Phone activity", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("service", "Connection", NotificationManager.IMPORTANCE_MIN))
    }

    private fun notify(dev: String, text: String, r: Request?) {
        channels(app)
        val open = PendingIntent.getActivity(app, dev.hashCode(),
            Intent(app, MainActivity::class.java).putExtra("device", dev), PendingIntent.FLAG_IMMUTABLE)
        val b = Notification.Builder(app, if (r != null) "requests" else "events")
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
