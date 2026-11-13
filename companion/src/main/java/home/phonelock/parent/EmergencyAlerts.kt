package home.phonelock.parent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Emergency alerts from a child's SOS button (`sos` messages; see the lock app's Emergency). A new alert
 * rings this phone like an alarm until this parent answers it, with a full-screen alert where Android allows
 * one and an ongoing notification otherwise. Later parts of the same alert add a fresh location or her
 * message; "Sent by mistake" stops the ringing. "I'm on it" tells her and the other parents (`sosack`) and
 * stops this phone's ringing only: each parent's phone rings until that parent answers.
 */
object EmergencyAlerts {
    /**
     * One alert from [dev]: [since] when she pressed SOS, [where] her latest known position with [address]
     * once looked up, [number] her phone's number (to call back), [messages] what she sent, [acks] parents on
     * it, [cancelled] sent by mistake, and whether this phone is still [ringing] for it.
     */
    data class Sos(val dev: String, val id: String, val since: Long, val where: Place? = null, val address: String? = null,
                   val number: String? = null, val battery: Int = -1, val messages: List<String> = emptyList(),
                   val acks: List<String> = emptyList(), val cancelled: Boolean = false, val ringing: Boolean = true)

    /** Alerts are shown for this long after she pressed SOS (her phone sends readings for 30 minutes). */
    private const val SHOW_MS = 2 * 3600_000L
    private const val CHANNEL = "emergency"
    private const val TAG = "EmergencyAlerts"

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("emergency", Context.MODE_PRIVATE) }
    private val _alerts = MutableStateFlow<List<Sos>>(emptyList())
    /** Recent alerts, newest first. */
    val alerts: StateFlow<List<Sos>> = _alerts.asStateFlow()

    fun init(c: Context) {
        app = c.applicationContext
        _alerts.value = load().filter { System.currentTimeMillis() - it.since < SHOW_MS }
    }

    /** The newest alert from [dev] still worth showing, if any. */
    fun current(dev: String): Sos? = _alerts.value.firstOrNull { it.dev == dev }

    // ---------- messages ----------

    /** An `sos` message from [d]'s device: starts an alert or adds to it. */
    fun handle(d: Device, m: JSONObject) {
        val id = m.optString("sos").takeIf { it.isNotEmpty() } ?: return
        val since = m.optLong("since", m.optLong("ts"))
        if (System.currentTimeMillis() - since > SHOW_MS) return // replayed from long ago
        val known = _alerts.value.firstOrNull { it.id == id }
        val base = known ?: Sos(d.id, id, since, ringing = m.optString("op") != "cancel")
        var next = base.copy(battery = if (m.has("batt")) m.optInt("batt") else base.battery,
            number = m.optString("number").takeIf { it.isNotEmpty() } ?: base.number)
        m.optJSONObject("where")?.takeIf { it.has("lat") }?.let { w ->
            val at = w.optLong("at")
            next = next.copy(where = Place(at, at, w.getDouble("lat"), w.getDouble("lng"), w.optInt("acc")), address = null)
        }
        when (m.optString("op")) {
            "msg" -> m.optString("text").takeIf { it.isNotEmpty() }?.let { next = next.copy(messages = next.messages + it) }
            "cancel" -> next = next.copy(cancelled = true, ringing = false)
        }
        put(next)

        val name = Hub.deviceName(d.id)
        when {
            known == null && !next.cancelled -> {
                Hub.addEvent(d.id, "Emergency alert from $name's phone", since)
                Alarm.start(app)
            }
            m.optString("op") == "cancel" -> {
                Hub.addEvent(d.id, "Emergency alert cancelled: sent by mistake")
                if (_alerts.value.none { it.ringing }) Alarm.stop(app)
            }
            m.optString("op") == "msg" -> Hub.addEvent(d.id, "Emergency message: ${m.optString("text")}")
        }
        notify(next)
        // The address makes the alert useful at a glance; look it up off this thread and update.
        next.where?.let { p -> thread(name = "sos-where") {
            val line = LocationHistory.addressNow(p) ?: return@thread
            update(id) { it.copy(address = line) }
            current(d.id)?.takeIf { it.id == id }?.let(::notify)
        } }
    }

    /** Another parent ([who]) answered alert `sos` on [dev]. */
    fun otherAck(dev: String, m: JSONObject, who: String) {
        val id = m.optString("sos")
        update(id) { if (who in it.acks) it else it.copy(acks = it.acks + who) }
        _alerts.value.firstOrNull { it.id == id }?.let(::notify)
    }

    // ---------- this parent's answers ----------

    /** "I'm on it": tells her and the other parents, and stops this phone ringing. */
    fun ack(id: String) {
        val a = _alerts.value.firstOrNull { it.id == id } ?: return
        val me = Hub.parentName.value.ifBlank { "A parent" }
        Hub.send(a.dev, Hub.msg("sosack").put("sos", id))
        update(id) { it.copy(ringing = false, acks = if (me in it.acks) it.acks else it.acks + me) }
        afterAnswer(id)
    }

    /** Stops this phone ringing for [id] without answering (the alert stays on screen). */
    fun silence(id: String) {
        update(id) { it.copy(ringing = false) }
        afterAnswer(id)
    }

    /** Clears alert [id] from this phone (after it's dealt with). */
    fun dismiss(id: String) {
        silence(id)
        _alerts.update { l -> l.filterNot { it.id == id } }
        save()
        app.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
    }

    /** Drops [dev]'s alerts (the device was removed). */
    fun forget(dev: String) {
        _alerts.value.filter { it.dev == dev }.forEach { dismiss(it.id) }
    }

    private fun afterAnswer(id: String) {
        if (_alerts.value.none { it.ringing }) Alarm.stop(app)
        _alerts.value.firstOrNull { it.id == id }?.let(::notify)
    }

    // ---------- notification ----------

    /** The alert's notification: full screen while ringing (where allowed), with I'm on it / Silence. */
    private fun notify(a: Sos) {
        val nm = app.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Emergency alerts", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "When a child presses SOS. The alarm plays separately, as an alarm."
            setSound(null, null) // the alarm service plays the sound
            enableVibration(false)
            setBypassDnd(true)
        })
        val open = PendingIntent.getActivity(app, a.id.hashCode(),
            Intent(app, EmergencyActivity::class.java).putExtra("sos", a.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

        /** A button that sends [kind] about this alert to [Actions]. */
        fun action(label: String, kind: String) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(app, (a.id + kind).hashCode(),
                Intent(app, Actions::class.java).putExtra("sos", a.id).putExtra("kind", kind),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

        val b = Notification.Builder(app, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title(a))
            .setContentText(summary(a))
            .setStyle(Notification.BigTextStyle().bigText(summary(a)))
            .setCategory(Notification.CATEGORY_ALARM)
            .setContentIntent(open)
            .setOngoing(a.ringing)
            .setOnlyAlertOnce(true)
        if (a.ringing) {
            b.setFullScreenIntent(open, true)
            b.addAction(action("I'm on it", "ack"))
            b.addAction(action("Silence", "silence"))
        }
        try {
            nm.notify(a.id.hashCode(), b.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification permission")
        }
    }

    /** "Emergency: Railyn", or "Cancelled: Railyn's alert". */
    fun title(a: Sos): String = if (a.cancelled) "Cancelled: ${Hub.deviceName(a.dev)}'s alert"
        else "Emergency: ${Hub.deviceName(a.dev)}"

    /** Where she is, her messages and who's on it, in one paragraph. */
    fun summary(a: Sos): String = buildString {
        if (a.cancelled) append("Sent by mistake. ")
        append(a.address?.let { "Near $it" } ?: if (a.where != null) "Location received" else "Waiting for her location")
        a.where?.let { append(" (${LocationHistory.clock(it.to)})") }
        append(".")
        if (a.battery >= 0) append(" Battery ${a.battery}%.")
        if (a.messages.isNotEmpty()) append(" She says: ").append(a.messages.joinToString(" · ")).append(".")
        if (a.acks.isNotEmpty()) append(" On it: ").append(a.acks.joinToString(", ")).append(".")
    }

    /** The notification's buttons. */
    class Actions : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val id = i.getStringExtra("sos") ?: return
            when (i.getStringExtra("kind")) {
                "ack" -> ack(id)
                "silence" -> silence(id)
            }
        }
    }

    // ---------- storage ----------

    private fun update(id: String, f: (Sos) -> Sos) {
        _alerts.update { l -> l.map { if (it.id == id) f(it) else it } }
        save()
    }

    private fun put(a: Sos) {
        _alerts.update { l -> listOf(a) + l.filterNot { it.id == a.id } }
        save()
    }

    private fun save() {
        val arr = JSONArray()
        _alerts.value.forEach { a ->
            arr.put(JSONObject().put("dev", a.dev).put("id", a.id).put("since", a.since).put("address", a.address ?: "")
                .put("number", a.number ?: "").put("battery", a.battery).put("messages", JSONArray(a.messages))
                .put("acks", JSONArray(a.acks)).put("cancelled", a.cancelled).put("ringing", a.ringing)
                .also { o -> a.where?.let { o.put("lat", it.lat).put("lng", it.lng).put("at", it.to).put("acc", it.accuracyM) } })
        }
        prefs.edit().putString("alerts", arr.toString()).apply()
    }

    private fun load(): List<Sos> = runCatching {
        val arr = JSONArray(prefs.getString("alerts", "[]"))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            fun list(n: String) = o.optJSONArray(n)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList()
            Sos(o.getString("dev"), o.getString("id"), o.getLong("since"),
                if (o.has("lat")) Place(o.optLong("at"), o.optLong("at"), o.getDouble("lat"), o.getDouble("lng"), o.optInt("acc")) else null,
                o.optString("address").takeIf { it.isNotEmpty() }, o.optString("number").takeIf { it.isNotEmpty() },
                o.optInt("battery", -1), list("messages"), list("acks"), o.optBoolean("cancelled"),
                // After a restart nothing is ringing any more; the alert is still shown.
                ringing = false)
        }
    }.getOrDefault(emptyList())

    // ---------- the alarm ----------

    /**
     * Plays the alarm sound on the alarm stream (which silent mode and Do Not Disturb normally let through)
     * and vibrates, as a foreground service so it keeps going with the app in the background. Stops when
     * every alert has been answered or silenced, or after [MAX_MS].
     */
    class Alarm : Service() {
        private var player: MediaPlayer? = null

        override fun onBind(intent: Intent?): IBinder? = null

        override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
            val n = Notification.Builder(this, CHANNEL_SERVICE)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setContentTitle("Emergency alarm")
                .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, EmergencyActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE))
                .build()
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_SERVICE, "Emergency alarm playing", NotificationManager.IMPORTANCE_LOW))
            startForeground(SERVICE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
            if (player == null) play()
            android.os.Handler(mainLooper).postDelayed({ stopSelf() }, MAX_MS)
            return START_NOT_STICKY
        }

        private fun play() {
            try {
                player = MediaPlayer().apply {
                    setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    setDataSource(this@Alarm, RingtoneManager.getActualDefaultRingtoneUri(this@Alarm, RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE))
                    isLooping = true
                    prepare()
                    start()
                }
            } catch (e: Exception) {
                Log.w(TAG, "alarm sound: $e") // the vibration and notification still go
            }
            getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }?.vibrate(
                VibrationEffect.createWaveform(longArrayOf(0, 900, 500), 0),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
        }

        override fun onDestroy() {
            player?.runCatching { stop(); release() }
            player = null
            getSystemService(Vibrator::class.java)?.cancel()
            super.onDestroy()
        }

        companion object {
            private const val CHANNEL_SERVICE = "emergency_service"
            private const val SERVICE_ID = 0x505
            /** Longest the alarm plays without an answer. */
            private const val MAX_MS = 15 * 60_000L

            fun start(c: Context) {
                runCatching { c.startForegroundService(Intent(c, Alarm::class.java)) }
                    .onFailure { Log.w(TAG, "alarm start: $it") }
            }

            fun stop(c: Context) {
                runCatching { c.stopService(Intent(c, Alarm::class.java)) }
            }
        }
    }
}
