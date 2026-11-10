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
            enabled = true
        } catch (e: Exception) {
            Log.w(TAG, "firebase: $e")
        }
    }

    /** The hub's address, or empty if this build has none. */
    val hub: String get() = BuildConfig.HUB_URL.trimEnd('/')

    /** Whether [d]'s messages arrive by push (it is on the hub and push works). */
    fun covers(d: Device) = enabled && hub.isNotEmpty() && d.server.trimEnd('/') == hub

    /** Subscribes to the FCM topics of pushed phones and unsubscribes from [removed] ones. */
    fun sync(devices: List<Device>, removed: List<Device> = emptyList()) {
        if (!enabled) return
        val fm = FirebaseMessaging.getInstance()
        devices.filter(::covers).forEach { fm.subscribeToTopic(it.topic) }
        removed.filter(::covers).forEach { fm.unsubscribeFromTopic(it.topic) }
    }

    /**
     * Fetches message [id] from [d]'s server, for the rare push that FCM truncated (over its 4 KB limit).
     * Blocks; call off the main thread.
     */
    fun fetchFull(d: Device, id: String) {
        try {
            // ntfy keeps messages 12 h; read the last hour and pick this one out.
            val h = URL("${d.server}/${d.topic}/json?poll=1&since=1h").openConnection() as HttpURLConnection
            h.connectTimeout = 15_000
            h.readTimeout = 30_000
            try {
                h.inputStream.bufferedReader().useLines { lines ->
                    lines.mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
                        .filter { it.optString("id") == id }
                        .forEach { m ->
                            val url = m.optJSONObject("attachment")?.optString("url")?.takeIf { it.isNotEmpty() }
                            Hub.handle(d.topic, m.optString("message"), url)
                        }
                }
            } finally {
                h.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "fetch $id: $e")
        }
    }
}

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
        Hub.handle(topic, data["message"] ?: return, data["attachment_url"])
    }

    override fun onNewToken(token: String) {
        // Topic subscriptions are tied to the token; renew them.
        Push.sync(Hub.devices.value)
    }
}
