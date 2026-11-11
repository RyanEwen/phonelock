package home.phonelock.parent

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

/**
 * Push delivery through the PhoneLock hub: the hub (self-hosted ntfy with Firebase) forwards each message
 * to the FCM topic named after the ntfy topic, so phones on the hub need no permanent background connection.
 * Phones on any other server (e.g. ntfy.sh) still use [ParentService]'s connection.
 */
object Push {
    /** True when this build has a Firebase project (secrets/firebase.properties) and it initialised. */
    @Volatile var enabled = false
        private set

    fun init(c: Context) {
        if (BuildConfig.FCM_APP_ID.isEmpty()) return
        try {
            val options = FirebaseOptions.Builder()
                .setApplicationId(BuildConfig.FCM_APP_ID)
                .setApiKey(BuildConfig.FCM_API_KEY)
                .setProjectId(BuildConfig.FCM_PROJECT_ID)
                .setGcmSenderId(BuildConfig.FCM_SENDER_ID)
                .build()
            if (FirebaseApp.getApps(c).isEmpty()) FirebaseApp.initializeApp(c, options)
            app = c.applicationContext
            prefs = c.getSharedPreferences("push", Context.MODE_PRIVATE)
            subscribed.addAll(prefs.getStringSet("subscribed", emptySet()) ?: emptySet())
            enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "firebase: $e")
        }
    }

    /** The hub's address, or empty if this build has none. */
    val hub: String get() = BuildConfig.HUB_URL.trimEnd('/')

    private lateinit var prefs: android.content.SharedPreferences

    /** Topics Firebase confirmed this app is subscribed to; only these count as reached by push. */
    private val subscribed = java.util.Collections.synchronizedSet(HashSet<String>())

    /** Whether [d] is on the hub, where push can reach it once subscribed. */
    private fun onHub(d: Device) = enabled && hub.isNotEmpty() && d.server.trimEnd('/') == hub

    /**
     * Whether [d]'s messages arrive by push: it is on the hub and Firebase confirmed the subscription.
     * Until then (no Play services, wrong project settings, offline at first start) the background
     * connection keeps working for it.
     */
    fun covers(d: Device) = onHub(d) && d.topic in subscribed

    /** Subscribes to the FCM topics of hub devices and unsubscribes from [removed] ones. */
    fun sync(devices: List<Device>, removed: List<Device> = emptyList()) {
        if (!enabled) return
        val fm = FirebaseMessaging.getInstance()
        devices.filter(::onHub).forEach { d ->
            fm.subscribeToTopic(d.topic).addOnCompleteListener { task ->
                if (task.isSuccessful) markSubscribed(d.topic, true)
                else Log.w(TAG, "subscribe ${d.topic}: ${task.exception}")
            }
        }
        removed.filter(::onHub).forEach { d ->
            fm.unsubscribeFromTopic(d.topic)
            markSubscribed(d.topic, false)
        }
    }

    private fun markSubscribed(topic: String, on: Boolean) {
        val changed = if (on) subscribed.add(topic) else subscribed.remove(topic)
        if (!changed) return
        prefs.edit().putStringSet("subscribed", HashSet(subscribed)).apply()
        // The background connection is only needed for devices push doesn't reach.
        ParentService.restart(app)
    }

    private lateinit var app: Context

    /**
     * Fetches messages for pushed devices that arrived while push was down or dropped (FCM discards
     * pending messages past its limit), from the hub's 12 h cache. Duplicates are ignored by [Hub].
     */
    fun catchUp() {
        if (!enabled) return
        val devices = Hub.devices.value.filter(::covers)
        if (devices.isEmpty()) return
        thread(name = "push-catchup") {
            devices.groupBy { it.server }.forEach { (server, devs) ->
                val last = Hub.lastNtfyTime
                val since = if (last > 0) (last - 60).toString() else "12h"
                poll("$server/${devs.joinToString(",") { it.topic }}/json?poll=1&since=$since") { m ->
                    Hub.lastNtfyTime = maxOf(Hub.lastNtfyTime, m.optLong("time"))
                    Hub.handle(m.optString("topic"), m.optString("message"), attachmentOf(m))
                }
            }
        }
    }

    private fun attachmentOf(m: JSONObject) = m.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() }

    /** Reads one ntfy poll response, calling [each] for every message event. Blocks. */
    private fun poll(url: String, each: (JSONObject) -> Unit) {
        try {
            val h = URL(url).openConnection() as HttpURLConnection
            h.connectTimeout = 15_000
            h.readTimeout = 30_000
            try {
                h.inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                        .filter { it.optString("event") == "message" }
                        .forEach(each)
                }
            } finally {
                h.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "poll: $e")
        }
    }

    /**
     * Fetches message [id] from [d]'s server, for the rare push that FCM truncated (over its 4 KB limit).
     * Blocks; call off the main thread.
     */
    fun fetchFull(d: Device, id: String) {
        // ntfy keeps messages 12 h; a late push may be that old.
        poll("${d.server}/${d.topic}/json?poll=1&since=12h") { m ->
            if (m.optString("id") == id) Hub.handle(d.topic, m.optString("message"), attachmentOf(m))
        }
    }}

/** Receives the hub's FCM pushes and hands them to [Hub] like a message from the live connection. */
class PushService : FirebaseMessagingService() {
    override fun onMessageReceived(msg: RemoteMessage) {
        val data = msg.data
        if (data["event"] != "message") return
        val topic = data["topic"] ?: return
        val d = Hub.devices.value.firstOrNull { it.topic == topic } ?: return
        val id = data["id"] ?: return
        if (data["truncated"] == "1") {
            thread(name = "push-fetch") { Push.fetchFull(d, id) }
            return
        }
        data["time"]?.toLongOrNull()?.let { Hub.lastNtfyTime = maxOf(Hub.lastNtfyTime, it) }
        Hub.handle(topic, data["message"] ?: return, data["attachment_url"])
    }

    /** FCM dropped pending messages (too many while offline): fetch them from the hub instead. */
    override fun onDeletedMessages() = Push.catchUp()

    override fun onNewToken(token: String) {
        // Topic subscriptions are tied to the token; renew them.
        Push.sync(Hub.devices.value)
    }
}
