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
    /** Normally allowed apps a parent blocked for a while: package -> end time. */
    val blocks: Map<String, Long> = emptyMap(),
    /** The lock app's version on the device, and a newer one downloaded and waiting to install (0 if none). */
    val version: Int = 0,
    val updateReady: Int = 0,
    /** A situation started by hand ("limited" / "bedtime") and when it ends; empty when none. */
    val manualMode: String = "",
    val manualUntil: Long = 0,
    /** Apps that can be uninstalled (installed on the device, not built in). */
    val removable: Set<String> = emptySet(),
    /** When parent mode ends on the device (0 when it's off). */
    val parentUntil: Long = 0,
    /** Call limits and safe list (from a full state). */
    val calls: CallSettings = CallSettings(),
    /** What the device has allowed the lock app: file access, reading its call log. */
    val canFiles: Boolean = false,
    val canCallLog: Boolean = false,
    /** Low-battery alert threshold in percent (0 = off, -1 = not reported yet); from a full state. */
    val battAlert: Int = -1,
    /** True once a full state (with app lists, table and schedule) has arrived; editing waits for it. */
    val full: Boolean = false,
    /** Whether the screen was on when this state was sent (null: the lock app is too old to say). */
    val screenOn: Boolean? = null,
    /** The app in front when this state was sent ("" for none) and when it was opened. */
    val using: String = "",
    val usingSince: Long = 0,
    /**
     * Parents' decisions about Google services screens (full state): a screen class or "area:" + an area's
     * key -> "ok", "block" or "ok:<until ms>" (allowed for a while).
     */
    val screenRules: Map<String, String> = emptyMap(),
    /** The groups of Google screens the device blocks unless a parent allows them (full state). */
    val screenAreas: List<ScreenArea> = emptyList(),
) {
    fun label(pkg: String) = labels[pkg] ?: pkg
}

/**
 * What the device last said about itself, from any of its messages: when it was last heard from ([seen],
 * its own clock), its battery level ([battery] -1 if unknown) as of [batteryAt], and when a live message
 * last arrived here ([heard], this phone's clock; compared with when this app asked, so a device clock
 * that runs a little behind can't make a reply look older than the question).
 */
data class Vitals(val seen: Long = 0, val battery: Int = -1, val charging: Boolean = false, val batteryAt: Long = 0,
                  val heard: Long = 0)

data class Request(val device: String, val req: String, val app: String, val label: String,
                   val minutes: Long, val ts: Long)
data class Event(val ts: Long, val text: String)

/** A group of Google services screens the device blocks by default ("account", "Manage your Google Account"). */
data class ScreenArea(val key: String, val name: String)

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Hub.init(this)
        AppIconCache.init(this)
        PhoneTools.init(this)
        DeviceData.init(this)
        ScreenAlerts.init(this)
        EmergencyAlerts.init(this)
        LocationHistory.init(this)
        AppTime.init(this)
        ChildSetup.init(this)
        AppUpdate.init(this)
        Push.init(this)
        Push.sync(Hub.devices.value)
        QuietWatch.init(this)
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
    private val _vitals = MutableStateFlow<Map<String, Vitals>>(emptyMap())
    val vitals: StateFlow<Map<String, Vitals>> = _vitals.asStateFlow()
    /** When this app last asked each device for its state, to notice a device that doesn't answer. */
    private val _askedAt = MutableStateFlow<Map<String, Long>>(emptyMap())
    val askedAt: StateFlow<Map<String, Long>> = _askedAt.asStateFlow()

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
        prefs.getString("vitals", null)?.let { s ->
            runCatching {
                val o = JSONObject(s)
                _vitals.value = o.keys().asSequence().associateWith { k ->
                    o.getJSONObject(k).let {
                        Vitals(it.optLong("seen"), it.optInt("battery", -1), it.optBoolean("charging"), it.optLong("batteryAt"),
                            it.optLong("heard"))
                    }
                }
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
        _vitals.update { it - id }
        // Everything kept about the device goes with it (the privacy policy promises this).
        LocationHistory.forget(id)
        DeviceData.forget(id)
        AppIconCache.forget(id)
        PhoneTools.forget(id)
        Diagnostics.forget(id)
        AppTime.forget(id)
        Pending.forget(id)
        ScreenAlerts.forget(id)
        QuietWatch.forget(id)
        EmergencyAlerts.forget(id)
        _requests.update { l -> l.filterNot { it.device == id } }
        saveDevices(); saveStates(); saveEvents(); saveRequests(); saveVitals()
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
    fun refresh(dev: String, full: Boolean = true) {
        _askedAt.update { it + (dev to System.currentTimeMillis()) }
        send(dev, msg("status").put("full", full))
    }

    /** Sets [dev]'s low-battery alert to [percent] (0 turns it off); shared by every parent of the device. */
    fun setBatteryAlert(dev: String, percent: Int) {
        editState(dev) { it.copy(battAlert = percent) }
        send(dev, msg("battery").put("alert", percent))
    }

    fun grant(dev: String, app: String, minutes: Long, req: String? = null) {
        // Show it at once (the latest grant or block on an app replaces the other); the device confirms.
        val until = System.currentTimeMillis() + minutes * 60_000
        editState(dev) { it.copy(grants = it.grants + (app to until), blocks = it.blocks - app) }
        send(dev, msg("grant").put("app", app).put("minutes", minutes).apply { req?.let { put("req", it) } })
        req?.let(::resolve)
    }

    /** Ends [app]'s allowed-for-a-while time, or every app's with "all"; shown at once. */
    fun revoke(dev: String, app: String) {
        editState(dev) { if (app == "all") it.copy(grants = emptyMap()) else it.copy(grants = it.grants - app) }
        send(dev, msg("revoke").put("app", app))
    }

    /** Takes a normally allowed [app] away for [minutes]; allowing it again ([grant] or [unblock]) ends that. */
    fun block(dev: String, app: String, minutes: Long) {
        val until = System.currentTimeMillis() + minutes * 60_000
        editState(dev) { it.copy(blocks = it.blocks + (app to until), grants = it.grants - app) }
        send(dev, msg("block").put("app", app).put("minutes", minutes))
    }

    /** Ends a pause on [app] early; shown at once. */
    fun unblock(dev: String, app: String) {
        editState(dev) { it.copy(blocks = it.blocks - app) }
        send(dev, msg("unblock").put("app", app))
    }

    fun deny(r: Request) {
        send(r.device, msg("deny").put("req", r.req).put("label", r.label))
        resolve(r.req)
    }

    fun home(dev: String, op: String, app: String) = send(dev, msg("home").put("op", op).put("app", app))

    /**
     * Allows ([on]) or disallows [app] in one situation ([bit]: MODE_NORMAL / MODE_LIMITED / MODE_BEDTIME).
     * Sent as a single change so quick taps from slightly old state don't undo each other.
     */
    fun appModeBit(dev: String, app: String, bit: Int, on: Boolean) {
        // Show the change at once; the device's next state confirms or corrects it.
        editState(dev) { st ->
            val cur = if (app in st.home) st.masks[app] ?: (MODE_NORMAL or MODE_LIMITED) else 0
            val mask = if (on) cur or bit else cur and bit.inv()
            when {
                mask == 0 -> st.copy(home = st.home - app, others = (st.others + app).distinct(), masks = st.masks - app)
                app !in st.home -> st.copy(home = st.home + app, others = st.others - app, masks = st.masks + (app to mask))
                else -> st.copy(masks = st.masks + (app to mask))
            }
        }
        send(dev, msg("appmode").put("app", app).put(if (on) "set" else "clear", bit))
    }

    /**
     * Applies [change] to the cached state of [dev] right away, for instant feedback. Its timestamp is
     * kept, so the device's next state (always newer) replaces it.
     */
    private fun editState(dev: String, change: (PhoneState) -> PhoneState) {
        _states.update { m -> m[dev]?.let { m + (dev to change(it)) } ?: m }
        saveStates()
    }

    /**
     * Records a decision about Google services screen or area [key] (a screen class, or "area:" + an area's
     * key) on [dev]: "ok" (allow it; for [minutes] if above 0, or until bedtime with [untilBedtime]), "block"
     * (send her back from it), or "" (forget the decision: back to the default).
     */
    fun setScreenRule(dev: String, key: String, rule: String, minutes: Int = 0, untilBedtime: Boolean = false) {
        // Shown at once; a timed allow's exact end comes with the device's reply.
        val shown = if (rule == "ok" && minutes > 0) "ok:${System.currentTimeMillis() + minutes * 60_000L}" else rule
        editState(dev) { st -> st.copy(screenRules = if (rule.isEmpty()) st.screenRules - key else st.screenRules + (key to shown)) }
        val m = msg("screenrule").put("rule", rule)
        if (key.startsWith(AREA_PREFIX)) m.put("area", key.removePrefix(AREA_PREFIX)) else m.put("screen", key)
        if (minutes > 0) m.put("minutes", minutes)
        if (untilBedtime) m.put("untilBedtime", true)
        send(dev, m)
    }

    /** Prefix of a Google screens area's key in [PhoneState.screenRules]. */
    const val AREA_PREFIX = "area:"

    /** Replaces the phone's weekly schedule. */
    fun setSchedule(dev: String, rules: List<ScheduleRule>) {
        // Show it at once; the device's next state confirms (or corrects) it.
        editState(dev) { it.copy(schedule = rules) }
        send(dev, msg("schedule").put("rules", ScheduleRule.toJson(rules)))
    }

    /**
     * Unlocks Settings and the Play Store on [dev] for [minutes] (0 ends parent mode now). It also ends when
     * the device's screen turns off.
     */
    fun parentMode(dev: String, minutes: Long) {
        editState(dev) {
            it.copy(parentMode = minutes > 0, parentUntil = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000 else 0)
        }
        send(dev, msg("parentmode").put("minutes", minutes))
    }

    /**
     * Starts limited hours or bedtime ([mode] "limited" / "bedtime") on [dev] for [minutes], on top of the
     * schedule (the stricter one applies). Shows it at once; the device confirms.
     */
    fun startMode(dev: String, mode: String, minutes: Long) {
        val until = System.currentTimeMillis() + minutes * 60_000
        editState(dev) { st ->
            val stricter = listOf(st.mode, mode).maxBy { modeBit(it) }
            st.copy(manualMode = mode, manualUntil = until, mode = stricter, modeUntil = until)
        }
        send(dev, msg("mode").put("mode", mode).put("minutes", minutes))
    }

    /** Ends a manually started situation; the schedule applies again (the device reports what that is). */
    fun endMode(dev: String) {
        editState(dev) { it.copy(manualMode = "", manualUntil = 0) }
        send(dev, msg("mode").put("mode", "none"))
    }

    /**
     * Recovery: turns USB and wireless debugging on on [dev] for [minutes] (0 turns them off), so it can be
     * repaired or updated over adb even if its screens can't be used. The device reports where to connect.
     */
    // The device reports how these went in Recent events; each says at once that it was sent.

    fun debugging(dev: String, minutes: Long) {
        send(dev, msg("debug").put("minutes", minutes))
        Undo.notify(if (minutes > 0) "Asked ${deviceName(dev)}'s device to turn on debugging" else "Asked ${deviceName(dev)}'s device to turn off debugging")
    }

    /** Asks [dev] to check for a lock app update now; it installs the next time the screen is off. */
    fun updateNow(dev: String) {
        send(dev, msg("update"))
        Undo.notify("Asked ${deviceName(dev)}'s device to check for an update. Results appear in Recent events.")
    }

    /** Uninstalls [app] from [dev] (removes it and its data; only for apps the device reports removable). */
    fun uninstall(dev: String, app: String) {
        send(dev, msg("uninstall").put("app", app))
        Undo.notify("Asked ${deviceName(dev)}'s device to uninstall ${_states.value[dev]?.label(app) ?: app}")
    }

    /** Sets the parent PIN on [dev] (4 to 8 digits). */
    fun setPin(dev: String, pin: String) {
        send(dev, msg("pin").put("pin", pin))
        Undo.notify("New parent PIN sent to ${deviceName(dev)}'s device")
    }

    /** Replaces the device's call settings (limits and safe list), showing the change at once. */
    fun setCalls(dev: String, calls: CallSettings) {
        editState(dev) { it.copy(calls = calls) }
        send(dev, msg("calls").put("limitOut", calls.limitOut).put("limitIn", calls.limitIn).put("safe", calls.safeJson()))
    }

    /** Puts [app] on ([on]) or takes it off the phone's "Give access" list and the child's Ask picker. */
    fun offer(dev: String, app: String, on: Boolean) {
        editState(dev) { st ->
            if (on) st.copy(notOffered = st.notOffered - app, others = (st.others + app).distinct())
            else st.copy(others = st.others - app, notOffered = (st.notOffered + app).distinct())
        }
        send(dev, msg("offer").put("app", app).put("on", on))
    }

    /** Longest sealed body sent inline; ntfy's message limit is 4096 bytes. */
    private const val MAX_INLINE = 3800

    /** Seals [o] with [dev]'s key and posts it to the phone's topic, logging failures to its activity. */
    internal fun send(dev: String, o: JSONObject) {
        val d = _devices.value.firstOrNull { it.id == dev } ?: return
        thread {
            try {
                val key = Crypto.key(d.secret)
                var body = Crypto.seal(key, o.toString())
                val h = URL("${d.server}/${d.topic}").openConnection() as HttpURLConnection
                h.requestMethod = "POST"
                h.doOutput = true
                h.connectTimeout = 15_000
                h.readTimeout = 30_000
                if (body.length > MAX_INLINE) {
                    // ntfy turns bodies over 4 KB into files the device wouldn't read: send a small "big"
                    // message carrying this one as an encrypted attachment (see PROTOCOL.md).
                    val wrapper = msg("big")
                    val blob = Crypto.sealBytes(key, o.toString().toByteArray(), "phonelock/blob/${wrapper.getString("id")}")
                    h.requestMethod = "PUT"
                    h.setRequestProperty("Filename", "blob.bin")
                    h.setRequestProperty("Message", Crypto.seal(key, wrapper.toString()))
                    h.outputStream.use { it.write(blob) }
                } else {
                    h.outputStream.use { it.write(body.toByteArray(Charsets.US_ASCII)) }
                }
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
        if (from == "phone") noteVitals(dev, m, ts)
        // Any reply ends the wait its screen may be showing (handlers below still check it's for them).
        if (from == "phone") Pending.answered(dev, m.optString("req"))
        when (m.optString("t")) {
            "state" -> if (from == "phone") {
                // Limits and today's app use are kept by their own module (which keeps the newest itself).
                AppTime.handleState(dev, m, ts)
                val s = parseState(m)
                val old = _states.value[dev]
                // States can arrive out of order (a full one travels as a slower attachment): keep the newest.
                // An older full state still brings what partial ones lack (app list, schedule…), so take those
                // parts from it rather than dropping it.
                if (old != null && s.updated < old.updated) {
                    if (s.full) {
                        _states.update { it + (dev to old.copy(others = s.others.filterNot { p -> p in old.home },
                            notOffered = s.notOffered, labels = s.labels + old.labels, masks = s.masks,
                            schedule = s.schedule, calls = s.calls, canFiles = s.canFiles, canCallLog = s.canCallLog,
                            removable = s.removable, battAlert = s.battAlert, screenRules = s.screenRules, screenAreas = s.screenAreas, full = true)) }
                        saveStates()
                    }
                    return
                }
                val merged = if (s.full || old == null) s
                    // A partial state carries the home list but not the others: keep the previous others, minus
                    // anything now on the home list, so an app can't be in both (and listed twice).
                    else s.copy(others = old.others.filterNot { it in s.home }, notOffered = old.notOffered, labels = old.labels + s.labels,
                        masks = old.masks, schedule = old.schedule, full = old.full, calls = old.calls,
                        canFiles = old.canFiles, canCallLog = old.canCallLog, removable = old.removable,
                        battAlert = old.battAlert, screenRules = old.screenRules, screenAreas = old.screenAreas)
                _states.update { it + (dev to merged) }
                saveStates()
                AppIconCache.ensure(d, merged.home + merged.others + merged.notOffered + merged.grants.keys)
            }
            "event" -> if (from == "phone") {
                if (m.optBoolean("off")) QuietWatch.noteOff(dev, ts)
                // A last-gasp notice (low battery, turning off) says where the phone last was: add the place to
                // the text, which means looking up the address first, off this thread.
                val where = m.optJSONObject("where")
                if (where != null && where.has("lat")) {
                    thread(name = "event-where") { onEvent(dev, m, ts, m.optString("text") + lastPlaceText(where)) }
                } else {
                    onEvent(dev, m, ts, m.optString("text"))
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
            "seen" -> if (from == "phone") ScreenAlerts.handle(d, m, attachmentUrl)
            "sos" -> if (from == "phone") EmergencyAlerts.handle(d, m)
            // Another parent answered an emergency alert (this phone's own answer is already shown).
            "sosack" -> if (from != "phone" && from != _parentName.value) EmergencyAlerts.otherAck(dev, m, from)
            "track" -> if (from == "phone") LocationHistory.handle(dev, m)
            "diag" -> if (from == "phone") Diagnostics.handle(dev, m)
            "usage" -> if (from == "phone") AppTime.handleUsage(dev, m)
            "icons" -> if (from == "phone") AppIconCache.handle(d, m, attachmentUrl)
            "calls", "contacts", "files", "file" -> if (from == "phone") DeviceData.handle(d, m, attachmentUrl)
            // Another parent answered a request.
            "grant", "deny" -> m.optString("req").takeIf { it.isNotEmpty() }?.let(::resolve)
        }
    }

    /** Records event [m] from [dev] as [text] and notifies when it's worth it. */
    private fun onEvent(dev: String, m: JSONObject, ts: Long, text: String) {
        addEvent(dev, text, ts)
        // A blocked Google screen: offer to let her through for a while, right from the notification.
        val blocked = m.optString("area").takeIf { it.isNotEmpty() }?.let { AREA_PREFIX + it }
            ?: m.optString("screen").takeIf { it.isNotEmpty() }
        if (blocked != null) {
            notify(dev, text, null, allowScreen = blocked)
            return
        }
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

    /** " · last near 12 Main St at 2:40 p.m." for a last-gasp notice's [where] {lat, lng, at}. */
    private fun lastPlaceText(where: JSONObject): String {
        val at = where.optLong("at")
        val p = Place(at, at, where.getDouble("lat"), where.getDouble("lng"))
        val address = LocationHistory.addressNow(p) ?: return ""
        return " · last near $address" + if (at > 0) " at ${LocationHistory.clock(at)}" else ""
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
            ScheduleRule.listFrom(m.optJSONArray("schedule")),
            m.optJSONObject("blocks")?.let { o -> o.keys().asSequence().associateWith { o.getLong(it) } } ?: emptyMap(),
            m.optInt("version"),
            m.optInt("updateReady"),
            m.optJSONObject("manual")?.optString("mode") ?: "",
            m.optJSONObject("manual")?.optLong("until") ?: 0,
            arr("removable").toSet(),
            m.optLong("parentUntil"),
            CallSettings.from(m.optJSONObject("calls")),
            m.optJSONObject("can")?.optBoolean("files") ?: false,
            m.optJSONObject("can")?.optBoolean("calllog") ?: false,
            m.optInt("battAlert", -1),
            m.has("others") || m.optBoolean("full"),
            if (m.has("screenOn")) m.optBoolean("screenOn") else null,
            m.optJSONObject("using")?.optString("app") ?: "",
            m.optJSONObject("using")?.optLong("since") ?: 0,
            m.optJSONObject("screenRules")?.let { o -> o.keys().asSequence().associateWith { o.getString(it) } } ?: emptyMap(),
            m.optJSONArray("screenAreas")?.let { a ->
                (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { ScreenArea(it.optString("key"), it.optString("name")) } }
            } ?: emptyList())
    }

    private fun stateJson(s: PhoneState) = JSONObject().put("ts", s.updated).put("name", s.name)
        .put("grants", JSONObject(s.grants as Map<*, *>)).put("home", JSONArray(s.home))
        .put("others", JSONArray(s.others)).put("labels", JSONObject(s.labels as Map<*, *>))
        .put("parent", s.parentMode).put("ringing", s.ringing)
        .put("notOffered", JSONArray(s.notOffered))
        .put("mode", s.mode).put("modeUntil", s.modeUntil)
        .put("masks", JSONObject(s.masks as Map<*, *>)).put("schedule", ScheduleRule.toJson(s.schedule))
        .put("blocks", JSONObject(s.blocks as Map<*, *>)).put("version", s.version).put("updateReady", s.updateReady)
        .put("manual", JSONObject().put("mode", s.manualMode).put("until", s.manualUntil))
        .put("removable", JSONArray(s.removable.toList())).put("parentUntil", s.parentUntil).put("calls", s.calls.toJson())
        .put("can", JSONObject().put("files", s.canFiles).put("calllog", s.canCallLog))
        .put("battAlert", s.battAlert).put("full", s.full).put("screenRules", JSONObject(s.screenRules as Map<*, *>))
        .put("screenAreas", JSONArray(s.screenAreas.map { JSONObject().put("key", it.key).put("name", it.name) }))
        .also { o ->
            s.screenOn?.let { o.put("screenOn", it) }
            if (s.using.isNotEmpty()) o.put("using", JSONObject().put("app", s.using).put("since", s.usingSince))
        }

    private fun saveStates() {
        val o = JSONObject()
        _states.value.forEach { (k, v) -> o.put(k, stateJson(v)) }
        prefs.edit().putString("states", o.toString()).apply()
    }

    /**
     * Any message from the device proves it was reachable at [ts], and most carry its battery level: keep
     * the newest of each, for the header's "seen 3 min ago · 54%".
     */
    private fun noteVitals(dev: String, m: JSONObject, ts: Long) {
        val now = System.currentTimeMillis()
        val before = maxOf(_vitals.value[dev]?.seen ?: 0, _states.value[dev]?.updated ?: 0)
        if (ts > before) QuietWatch.heard(dev, before)
        _vitals.update { all ->
            val v = all[dev] ?: Vitals()
            var next = if (ts > v.seen) v.copy(seen = ts) else v
            // An answer is a new message at least as recent as anything heard before, timed by this phone's clock
            // when it arrives (the device's own clock may be off). Replays were already dropped as seen.
            if (ts >= v.seen) next = next.copy(heard = now)
            if (m.has("batt") && ts >= v.batteryAt) {
                next = next.copy(battery = m.optInt("batt", -1), charging = m.optBoolean("chg"), batteryAt = ts)
            }
            all + (dev to next)
        }
        saveVitals()
    }

    private fun saveVitals() {
        val o = JSONObject()
        _vitals.value.forEach { (k, v) ->
            o.put(k, JSONObject().put("seen", v.seen).put("battery", v.battery).put("charging", v.charging)
                .put("batteryAt", v.batteryAt).put("heard", v.heard))
        }
        prefs.edit().putString("vitals", o.toString()).apply()
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

    /** Activity entries kept per device; the screen shows a few at a time (CappedList). */
    /** About a week of events (they also fill Activity's Events), at a few dozen a day. */
    private const val MAX_EVENTS = 1000

    fun addEvent(dev: String, text: String, ts: Long = System.currentTimeMillis()) {
        _events.update { m -> m + (dev to (listOf(Event(ts, text)) + (m[dev] ?: emptyList())).take(MAX_EVENTS)) }
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
    internal fun notify(dev: String, text: String, r: Request?, channel: String? = null, allowScreen: String? = null) {
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
        if (allowScreen != null) {
            b.addAction(Notification.Action.Builder(null, "Allow for 30 min", PendingIntent.getBroadcast(app, nid,
                Intent(app, ActionReceiver::class.java).putExtra("kind", "allowscreen").putExtra("device", dev)
                    .putExtra("screen", allowScreen).putExtra("nid", nid),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build())
        }
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
