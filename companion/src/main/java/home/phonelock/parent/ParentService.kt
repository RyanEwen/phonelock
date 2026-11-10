package home.phonelock.parent

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Holds one streaming ntfy connection per server, subscribed to every paired phone's topic,
 * so requests and alerts arrive immediately.
 */
class ParentService : Service() {
    @Volatile private var generation = 0
    private val conns = mutableListOf<HttpURLConnection>()

    companion object {
        fun start(c: Context) {
            if (Hub.devices.value.isEmpty()) return
            runCatching { c.startForegroundService(Intent(c, ParentService::class.java).setAction("restart")) }
                .onFailure { Log.w(TAG, "start: $it") }
        }
        fun stop(c: Context) { c.stopService(Intent(c, ParentService::class.java)) }
        fun restart(c: Context) = start(c)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Listening for paired phones")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        // (Re)subscribe with the current device list.
        val gen = ++generation
        synchronized(conns) { conns.forEach { it.disconnect() }; conns.clear() }
        Hub.devices.value.groupBy { it.server }.forEach { (server, devs) ->
            val topics = devs.joinToString(",") { it.topic }
            thread(name = "ntfy") { loop(gen, server, topics) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        generation++
        synchronized(conns) { conns.forEach { it.disconnect() }; conns.clear() }
        Hub.connected.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop(gen: Int, server: String, topics: String) {
        var backoff = 2_000L
        var first = true
        while (gen == generation) {
            try {
                val last = Hub.lastNtfyTime
                val since = if (last > 0) (last - 60).toString() else "1h"
                val h = URL("$server/$topics/json?since=$since").openConnection() as HttpURLConnection
                synchronized(conns) { conns.add(h) }
                h.connectTimeout = 20_000
                h.readTimeout = 120_000
                h.inputStream.bufferedReader().use { r ->
                    Hub.connected.value = true
                    backoff = 2_000
                    if (first) { Hub.refreshAll(); first = false }
                    while (gen == generation) {
                        val line = r.readLine() ?: break
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (o.optString("event") != "message") continue
                        Hub.lastNtfyTime = maxOf(Hub.lastNtfyTime, o.optLong("time"))
                        val attachment = o.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() }
                        Hub.handle(o.optString("topic"), o.optString("message"), attachment)
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ntfy: $e")
            }
            if (gen != generation) return
            Hub.connected.value = false
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(120_000)
        }
    }
}

class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        Hub.answer(i.getStringExtra("req") ?: return, i.getStringExtra("kind") ?: return,
            i.getLongExtra("minutes", 60))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) = ParentService.start(c)
}
