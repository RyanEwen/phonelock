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

/** Holds a streaming connection to the ntfy topic so requests and alerts arrive immediately. */
class ParentService : Service() {
    @Volatile private var running = false
    @Volatile private var conn: HttpURLConnection? = null

    companion object {
        fun start(c: Context) {
            if (Hub.config.value == null) return
            runCatching { c.startForegroundService(Intent(c, ParentService::class.java)) }
                .onFailure { Log.w(TAG, "start: $it") }
        }
        fun stop(c: Context) { c.stopService(Intent(c, ParentService::class.java)) }
        fun restart(c: Context) { stop(c); start(c) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Hub.channels(this)
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, "service")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle("Listening for her phone")
            .setContentIntent(open)
            .setOngoing(true)
            .build()
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        if (!running) {
            running = true
            thread(name = "ntfy") { loop() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        conn?.disconnect()
        Hub.connected.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun loop() {
        var backoff = 2_000L
        var first = true
        while (running) {
            val c = Hub.config.value ?: return
            try {
                val since = Hub.lastNtfyId ?: (System.currentTimeMillis() / 1000 - 3600).toString()
                val h = URL("${c.server}/${c.topic}/json?since=$since").openConnection() as HttpURLConnection
                conn = h
                h.connectTimeout = 20_000
                h.readTimeout = 120_000
                h.inputStream.bufferedReader().use { r ->
                    Hub.connected.value = true
                    backoff = 2_000
                    if (first) { Hub.refresh(true); first = false }
                    while (running) {
                        val line = r.readLine() ?: break
                        val o = runCatching { JSONObject(line) }.getOrNull() ?: continue
                        if (o.optString("event") != "message") continue
                        Hub.lastNtfyId = o.optString("id")
                        Hub.handle(o.optString("message"))
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "ntfy: $e")
            }
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
