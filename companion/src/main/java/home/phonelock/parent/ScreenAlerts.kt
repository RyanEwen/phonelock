package home.phonelock.parent

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.concurrent.thread

/**
 * Unfamiliar Google services screens the child's device reported (a `seen` message, see the lock app's
 * ScreenGuard), each with a screenshot when one could be taken. A parent marks one OK (never reported
 * again), blocks it (she's sent back from it from then on), or dismisses it (reported again next day if it
 * opens again). Kept until decided, on this phone only; screenshots are files in this app's storage.
 */
object ScreenAlerts {
    /**
     * One report: [screen] is the full class name the decision is about, [name] a short readable form,
     * [from] the app she opened it from (may be empty), [image] the saved screenshot (null if none, then
     * [error] may say why).
     */
    data class Alert(val id: String, val dev: String, val ts: Long, val screen: String, val name: String,
                     val from: String, val image: String?, val error: String?)

    private const val TAG = "ScreenAlerts"
    private const val MAX_PER_DEVICE = 20

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("screenalerts", Context.MODE_PRIVATE) }
    private val dir by lazy { File(app.filesDir, "screen-alerts").apply { mkdirs() } }

    private val _alerts = MutableStateFlow<List<Alert>>(emptyList())
    /** Undecided reports from every device, newest first. */
    val alerts: StateFlow<List<Alert>> = _alerts.asStateFlow()

    fun init(c: Context) {
        app = c.applicationContext
        _alerts.value = load()
    }

    /** A `seen` message from [d]; fetches its screenshot (if any) before showing and announcing it. */
    fun handle(d: Device, m: JSONObject, attachmentUrl: String?) {
        val id = m.optString("id")
        if (id.isEmpty() || _alerts.value.any { it.id == id }) return
        thread(name = "seen") {
            val image = attachmentUrl?.let { url ->
                runCatching { Attachments.fetch(d, url, id) }.onFailure { Log.w(TAG, "fetch: $it") }.getOrNull()
                    ?.let { jpeg -> File(dir, "$id.jpg").apply { writeBytes(jpeg) }.path }
            }
            val a = Alert(id, d.id, m.optLong("ts", System.currentTimeMillis()), m.optString("screen"),
                m.optString("name"), m.optString("from"), image,
                m.optString("error").takeIf { it.isNotEmpty() } ?: if (image == null && attachmentUrl != null) "Couldn't open the screenshot" else null)
            add(a)
            val where = if (a.from.isNotEmpty()) " from ${a.from}" else ""
            Hub.addEvent(a.dev, "Opened an unfamiliar Google services screen$where (${a.name})", a.ts)
            notify(a)
        }
    }

    /**
     * A parent's decision on alert [id]: "ok" or "block" is sent to the device and settles every report of
     * the same screen; anything else ("dismiss") only clears this report.
     */
    fun decide(id: String, kind: String) {
        val a = _alerts.value.firstOrNull { it.id == id } ?: return cancel(id)
        when (kind) {
            "ok", "block" -> {
                Hub.setScreenRule(a.dev, a.screen, kind)
                remove { it.dev == a.dev && it.screen == a.screen }
                Undo.notify(if (kind == "ok") "Allowed; you won't be told about this screen again" else "Blocked")
            }
            else -> remove { it.id == id }
        }
    }

    /** A short readable name for screen class [cls] ("accountsettings › AccountDetails"), as the device writes it. */
    fun screenName(cls: String): String {
        val rest = cls.removePrefix("com.google.android.gms.")
        val name = rest.substringAfterLast('.').removeSuffix("Activity")
        val area = if ('.' in rest) rest.substringBefore('.') else ""
        return if (area.isEmpty()) name else "$area › $name"
    }

    /** Drops everything about [dev] (the device was removed). */
    fun forget(dev: String) = remove { it.dev == dev }

    // ---------- storage ----------

    /** Adds [a], replacing an older report of the same screen on the same device. */
    private fun add(a: Alert) {
        remove { it.dev == a.dev && it.screen == a.screen }
        _alerts.update { l ->
            val all = listOf(a) + l
            // Keep the newest few per device; delete the screenshots of the rest.
            val keep = all.groupBy { it.dev }.values.flatMap { it.take(MAX_PER_DEVICE) }.toSet()
            all.filterNot { it in keep }.forEach { it.image?.let(::File)?.delete() }
            all.filter { it in keep }
        }
        save()
    }

    /** Removes the reports matching [which], with their screenshots and notifications. */
    private fun remove(which: (Alert) -> Boolean) {
        val gone = _alerts.value.filter(which)
        if (gone.isEmpty()) return
        gone.forEach {
            it.image?.let(::File)?.delete()
            cancel(it.id)
        }
        _alerts.update { l -> l.filterNot(which) }
        save()
    }

    private fun save() {
        val a = JSONArray()
        _alerts.value.forEach {
            a.put(JSONObject().put("id", it.id).put("dev", it.dev).put("ts", it.ts).put("screen", it.screen)
                .put("name", it.name).put("from", it.from).put("image", it.image ?: "").put("error", it.error ?: ""))
        }
        prefs.edit().putString("alerts", a.toString()).apply()
    }

    private fun load(): List<Alert> = runCatching {
        val a = JSONArray(prefs.getString("alerts", "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            Alert(o.getString("id"), o.getString("dev"), o.getLong("ts"), o.getString("screen"), o.getString("name"),
                o.optString("from"), o.optString("image").takeIf { it.isNotEmpty() && File(it).exists() },
                o.optString("error").takeIf { it.isNotEmpty() })
        }
    }.getOrDefault(emptyList())

    // ---------- notification ----------

    /** Announces [a] with its screenshot and "Mark OK" / "Block" buttons. */
    private fun notify(a: Alert) {
        Hub.channels(app)
        val open = PendingIntent.getActivity(app, a.id.hashCode(),
            Intent(app, MainActivity::class.java).putExtra("device", a.dev), PendingIntent.FLAG_IMMUTABLE)

        /** A button that sends decision [kind] about this alert. */
        fun action(label: String, kind: String, code: Int) = Notification.Action.Builder(null, label,
            PendingIntent.getBroadcast(app, code,
                Intent(app, ActionReceiver::class.java).putExtra("alert", a.id).putExtra("kind", kind),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()

        val where = if (a.from.isNotEmpty()) " from ${a.from}" else ""
        val b = Notification.Builder(app, "requests")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(Hub.deviceName(a.dev))
            .setContentText("Opened an unfamiliar Google screen$where")
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(action("Mark OK", "ok", a.id.hashCode() + 1))
            .addAction(action("Block", "block", a.id.hashCode() + 2))
        a.image?.let { BitmapFactory.decodeFile(it) }?.let { bmp ->
            b.setLargeIcon(bmp).setStyle(Notification.BigPictureStyle().bigPicture(bmp))
        }
        try {
            app.getSystemService(NotificationManager::class.java).notify(a.id.hashCode(), b.build())
        } catch (e: SecurityException) {
            Log.w(TAG, "no notification permission")
        }
    }

    private fun cancel(id: String) = app.getSystemService(NotificationManager::class.java).cancel(id.hashCode())
}
