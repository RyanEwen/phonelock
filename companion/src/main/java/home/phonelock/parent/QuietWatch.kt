package home.phonelock.parent

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlin.concurrent.thread

/**
 * Tells this parent when a child's device has gone quiet: no message at all (its location check-ins come
 * every 15 minutes) for longer than this parent's chosen time, so a dead battery, a phone left somewhere or
 * lost signal is noticed without anyone opening the app. The alert says where and when it was last heard
 * from and its battery then; once it's heard from again, a short "back" notice follows.
 *
 * Runs from an alarm every 15 minutes on this phone. It first fetches anything the hub holds (so a parent
 * phone that was offline doesn't mistake its own gap for the child's), and stays silent while this phone has
 * no connection itself, or when the device said it was turning off (that notice already went out).
 * The choice is per parent and per device, kept on this phone only.
 */
object QuietWatch {
    /** Choices offered in Settings, in minutes; 0 is "Off". */
    val CHOICES = listOf(0, 30, 60, 120, 240)
    private const val DEFAULT_MINUTES = 60
    private const val CHECK_EVERY_MS = 15 * 60_000L
    /** A shutdown notice this close to the last message means the silence is the phone being off. */
    private const val OFF_MARGIN_MS = 60_000L
    private const val TAG = "QuietWatch"

    private lateinit var app: Context
    private val prefs by lazy { app.getSharedPreferences("quiet", Context.MODE_PRIVATE) }

    private val _minutes = MutableStateFlow<Map<String, Int>>(emptyMap())
    /** Device -> this parent's chosen quiet time in minutes (absent: the default). */
    val minutes: StateFlow<Map<String, Int>> = _minutes.asStateFlow()

    fun init(c: Context) {
        app = c.applicationContext
        _minutes.value = prefs.all.filterKeys { it.startsWith("min_") }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix("min_") to it } }.toMap()
        schedule()
    }

    /** This parent's quiet time for [dev] in minutes; 0 means no alert. */
    fun minutesFor(dev: String): Int = _minutes.value[dev] ?: DEFAULT_MINUTES

    fun setMinutes(dev: String, m: Int) {
        prefs.edit().putInt("min_$dev", m).apply()
        _minutes.update { it + (dev to m) }
    }

    /** The device said it was turning off at [ts] (its clock). */
    fun noteOff(dev: String, ts: Long) {
        prefs.edit().putLong("off_$dev", ts).apply()
    }

    /**
     * A message from [dev] arrived: if a quiet alert went out for the silence before it, say it's back.
     * [lastSeen] is when it was heard from before this message.
     */
    fun heard(dev: String, lastSeen: Long) {
        if (prefs.getLong("alerted_$dev", 0) == 0L) return
        prefs.edit().remove("alerted_$dev").apply()
        val gap = System.currentTimeMillis() - lastSeen
        Hub.notify(dev, "Heard from the phone again" + if (lastSeen > 0) " after ${duration(gap)}" else "", null)
    }

    /** Drops this phone's settings for [dev] (the device was removed). */
    fun forget(dev: String) {
        prefs.edit().remove("min_$dev").remove("off_$dev").remove("alerted_$dev").apply()
        _minutes.update { it - dev }
    }

    // ---------- the check ----------

    /** Sets the next check in 15 minutes; allowed while the phone idles, so it isn't put off for hours. */
    private fun schedule() {
        val am = app.getSystemService(AlarmManager::class.java)
        val pi = PendingIntent.getBroadcast(app, 0, Intent(app, Check::class.java), PendingIntent.FLAG_IMMUTABLE)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + CHECK_EVERY_MS, pi)
    }

    /** The alarm: runs [check] off the main thread, then sets the next one. */
    class Check : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val pending = goAsync()
            thread(name = "quiet-check") {
                try {
                    check()
                } catch (e: Exception) {
                    Log.w(TAG, "check: $e")
                } finally {
                    schedule()
                    pending.finish()
                }
            }
        }
    }

    /** Alerts about each device quiet for longer than its chosen time, once per silence. Blocks. */
    private fun check() {
        val devices = Hub.devices.value
        if (devices.isEmpty() || !online()) return
        // Anything the hub kept while this phone wasn't listening counts as hearing from the device.
        Push.catchUpNow()

        val now = System.currentTimeMillis()
        devices.forEach { d ->
            val limit = minutesFor(d.id) * 60_000L
            if (limit == 0L) return@forEach
            val vitals = Hub.vitals.value[d.id] ?: Vitals()
            val seen = maxOf(vitals.seen, Hub.states.value[d.id]?.updated ?: 0)
            if (seen == 0L || now - seen < limit) return@forEach
            if (prefs.getLong("alerted_${d.id}", 0) == seen) return@forEach
            if (prefs.getLong("off_${d.id}", 0) >= seen - OFF_MARGIN_MS) return@forEach

            prefs.edit().putLong("alerted_${d.id}", seen).apply()
            Hub.notify(d.id, quietText(d.id, now - seen, vitals), null)
        }
    }

    /** "No word from the phone for 1 h 10 min. Last near 12 Main St at 2:40 p.m. Battery 8%." */
    private fun quietText(dev: String, gap: Long, v: Vitals): String = buildString {
        append("No word from the phone for ${duration(gap)}.")
        LocationHistory.places.value[dev]?.firstOrNull()?.let { p ->
            val where = LocationHistory.addressNow(p) ?: "its last location"
            append(" Last near $where at ${LocationHistory.clock(p.to)}.")
        }
        if (v.battery >= 0) append(" Battery ${v.battery}%${if (v.charging) ", charging" else ""}.")
    }

    /** Whether this phone itself has a working internet connection. */
    private fun online(): Boolean {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** "45 min", "1 h 10 min", "3 h". */
    private fun duration(ms: Long): String {
        val m = ms / 60_000
        return when {
            m < 60 -> "$m min"
            m % 60 == 0L -> "${m / 60} h"
            else -> "${m / 60} h ${m % 60} min"
        }
    }
}
