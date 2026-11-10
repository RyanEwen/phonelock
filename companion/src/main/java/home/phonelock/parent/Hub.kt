package home.phonelock.parent

import android.app.Application
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

data class Config(val server: String, val topic: String, val secret: String, val name: String)

data class PhoneState(
    val updated: Long = 0,
    val grants: Map<String, Long> = emptyMap(),
    val home: List<String> = emptyList(),
    val others: List<String> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val parentMode: Boolean = false,
) {
    fun label(pkg: String) = labels[pkg] ?: pkg
}

data class Request(val req: String, val app: String, val label: String, val minutes: Long, val ts: Long)
data class Event(val ts: Long, val text: String)

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
    }
}

/** Process-wide state shared by the UI, the listener service and notification actions. */
object Hub {
    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("hub", Context.MODE_PRIVATE) }

    private val _config = MutableStateFlow<Config?>(null)
    val config: StateFlow<Config?> = _config.asStateFlow()
    private val _state = MutableStateFlow(PhoneState())
    val state: StateFlow<PhoneState> = _state.asStateFlow()
    private val _requests = MutableStateFlow<List<Request>>(emptyList())
    val requests: StateFlow<List<Request>> = _requests.asStateFlow()
    private val _events = MutableStateFlow<List<Event>>(emptyList())
    val events: StateFlow<List<Event>> = _events.asStateFlow()
    val connected = MutableStateFlow(false)

    private val sentIds = ArrayDeque<String>()
    private val seenIds = ArrayDeque<String>()

    fun init(c: Context) {
        app = c.applicationContext
        _config.value = prefs.getString("config", null)?.let { parseConfig(JSONObject(it)) }
        prefs.getString("state", null)?.let { runCatching { _state.value = parseState(JSONObject(it)) } }
        prefs.getString("requests", null)?.let { s ->
            val a = JSONArray(s)
            _requests.value = (0 until a.length()).map { a.getJSONObject(it) }.map {
                Request(it.getString("req"), it.getString("app"), it.optString("label"),
                    it.optLong("minutes", 60), it.optLong("ts"))
            }.filter { System.currentTimeMillis() - it.ts < 12 * 3600_000L }
        }
        prefs.getString("events", null)?.let { s ->
            val a = JSONArray(s)
            _events.value = (0 until a.length()).map { a.getJSONObject(it) }
                .map { Event(it.getLong("ts"), it.getString("text")) }
        }
    }

    // ---------- pairing ----------

    /** phonelock://pair?s=<server>&t=<topic>&k=<secret> */
    fun parsePairing(link: String): Triple<String, String, String>? = runCatching {
        val u = Uri.parse(link.trim())
        if (u.scheme != "phonelock" || u.host != "pair") return null
        val s = u.getQueryParameter("s") ?: "https://ntfy.sh"
        val t = u.getQueryParameter("t")!!
        val k = u.getQueryParameter("k")!!
        Crypto.key(k)
        Triple(s, t, k)
    }.getOrNull()

    fun pair(server: String, topic: String, secret: String, name: String) {
        val c = Config(server.trimEnd('/'), topic, secret, name.trim().ifEmpty { "Parent" })
        prefs.edit().putString("config", JSONObject()
            .put("server", c.server).put("topic", c.topic).put("secret", c.secret).put("name", c.name)
            .toString()).putString("ntfy_last_id", null).apply()
        _config.value = c
        ParentService.restart(app)
    }

    fun unpair() {
        prefs.edit().clear().apply()
        _config.value = null
        _state.value = PhoneState()
        _requests.value = emptyList()
        _events.value = emptyList()
        ParentService.stop(app)
    }

    private fun parseConfig(o: JSONObject) =
        Config(o.getString("server"), o.getString("topic"), o.getString("secret"), o.getString("name"))

    var lastNtfyId: String?
        get() = prefs.getString("ntfy_last_id", null)
        set(v) { prefs.edit().putString("ntfy_last_id", v).apply() }

    // ---------- outgoing ----------

    private fun msg(type: String): JSONObject {
        val id = Crypto.randomId()
        synchronized(sentIds) { sentIds.addLast(id); while (sentIds.size > 200) sentIds.removeFirst() }
        return JSONObject().put("v", 1).put("t", type).put("id", id)
            .put("ts", System.currentTimeMillis()).put("from", config.value?.name ?: "Parent")
    }

    fun refresh(full: Boolean = true) = send(msg("status").put("full", full))

    fun grant(app: String, minutes: Long, req: String? = null) {
        send(msg("grant").put("app", app).put("minutes", minutes).apply { req?.let { put("req", it) } })
        req?.let(::resolve)
    }

    fun revoke(app: String) = send(msg("revoke").put("app", app))

    fun deny(r: Request) {
        send(msg("deny").put("req", r.req).put("label", r.label))
        resolve(r.req)
    }

    fun home(op: String, app: String) = send(msg("home").put("op", op).put("app", app))

    private fun send(o: JSONObject) {
        val c = config.value ?: return
        thread {
            try {
                val body = Crypto.seal(Crypto.key(c.secret), o.toString())
                val h = URL("${c.server}/${c.topic}").openConnection() as HttpURLConnection
                h.requestMethod = "POST"
                h.doOutput = true
                h.connectTimeout = 15_000
                h.readTimeout = 15_000
                h.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
                val code = h.responseCode
                h.disconnect()
                if (code !in 200..299) addEvent("Couldn't send (HTTP $code)")
            } catch (e: Exception) {
                addEvent("Couldn't send: ${e.message}")
            }
        }
    }

    // ---------- incoming ----------

    fun handle(body: String) {
        val c = config.value ?: return
        val json = Crypto.open(Crypto.key(c.secret), body) ?: return
        val m = runCatching { JSONObject(json) }.getOrNull() ?: return
        val id = m.optString("id")
        synchronized(sentIds) { if (id in sentIds) return }
        synchronized(seenIds) {
            if (id in seenIds) return
            seenIds.addLast(id); while (seenIds.size > 500) seenIds.removeFirst()
        }
        val from = m.optString("from")
        val ts = m.optLong("ts")
        when (m.optString("t")) {
            "state" -> if (from == "phone") {
                val s = parseState(m)
                // A partial state keeps the last known "others" list.
                val merged = if (m.has("others")) s else s.copy(
                    others = _state.value.others, labels = _state.value.labels + s.labels)
                _state.value = merged
                prefs.edit().putString("state", m.apply {
                    if (!has("others")) put("others", JSONArray(merged.others))
                    put("labels", JSONObject(merged.labels as Map<*, *>))
                }.toString()).apply()
            }
            "event" -> if (from == "phone") {
                val text = m.optString("text")
                addEvent(text, ts)
                // Don't buzz for our own actions.
                if (!text.startsWith("${c.name}:")) notify(text, null)
            }
            "req" -> if (from == "phone") {
                val r = Request(m.getString("req"), m.getString("app"), m.optString("label"),
                    m.optLong("minutes", 60), ts)
                if (_requests.value.none { it.req == r.req }) {
                    _requests.update { it + r }
                    saveRequests()
                    addEvent("Asked for ${r.label} (${fmtMinutes(r.minutes)})", ts)
                    notify("Asks for ${r.label} for ${fmtMinutes(r.minutes)}", r)
                }
            }
            // The other parent answered a request.
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
        return PhoneState(m.optLong("ts"), grants, arr("home"), arr("others"), labels, m.optBoolean("parent"))
    }

    private fun resolve(req: String) {
        _requests.update { l -> l.filterNot { it.req == req } }
        saveRequests()
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.cancel(req.hashCode())
    }

    private fun saveRequests() {
        val a = JSONArray()
        _requests.value.forEach {
            a.put(JSONObject().put("req", it.req).put("app", it.app).put("label", it.label)
                .put("minutes", it.minutes).put("ts", it.ts))
        }
        prefs.edit().putString("requests", a.toString()).apply()
    }

    fun addEvent(text: String, ts: Long = System.currentTimeMillis()) {
        _events.update { (listOf(Event(ts, text)) + it).take(50) }
        val a = JSONArray()
        _events.value.forEach { a.put(JSONObject().put("ts", it.ts).put("text", it.text)) }
        prefs.edit().putString("events", a.toString()).apply()
    }

    // ---------- notifications ----------

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("requests", "Requests", NotificationManager.IMPORTANCE_HIGH))
        nm.createNotificationChannel(NotificationChannel("events", "Phone activity", NotificationManager.IMPORTANCE_DEFAULT))
        nm.createNotificationChannel(NotificationChannel("service", "Connection", NotificationManager.IMPORTANCE_MIN))
    }

    private fun notify(text: String, r: Request?) {
        channels(app)
        val open = PendingIntent.getActivity(app, 0, Intent(app, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val b = android.app.Notification.Builder(app, if (r != null) "requests" else "events")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(text)
            .setContentIntent(open)
            .setAutoCancel(true)
        if (r != null) {
            fun action(label: String, kind: String, minutes: Long, code: Int) =
                android.app.Notification.Action.Builder(null, label, PendingIntent.getBroadcast(app, code,
                    Intent(app, ActionReceiver::class.java).putExtra("kind", kind).putExtra("req", r.req)
                        .putExtra("minutes", minutes),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
            val base = r.req.hashCode()
            b.addAction(action("Allow ${fmtMinutes(r.minutes)}", "allow", r.minutes, base))
            if (r.minutes != 30L) b.addAction(action("30 min", "allow", 30, base + 1))
            b.addAction(action("Deny", "deny", 0, base + 2))
        }
        val id = r?.req?.hashCode() ?: (System.currentTimeMillis() and 0x7fffffff).toInt()
        try {
            app.getSystemService(NotificationManager::class.java).notify(id, b.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification permission")
        }
    }

    fun answer(reqId: String, kind: String, minutes: Long) {
        val r = _requests.value.firstOrNull { it.req == reqId } ?: run {
            app.getSystemService(NotificationManager::class.java).cancel(reqId.hashCode()); return
        }
        if (kind == "allow") grant(r.app, minutes, r.req) else deny(r)
    }
}

fun fmtMinutes(m: Long): String = when {
    m % 60 == 0L -> "${m / 60} h"
    m > 60 -> "${m / 60} h ${m % 60} min"
    else -> "$m min"
}
