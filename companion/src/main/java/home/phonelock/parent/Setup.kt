package home.phonelock.parent

import android.net.Uri
import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64

/**
 * Creates the pairing for a new child's phone and the QR codes that carry it, so a family needs no PC:
 *  - the setup code, scanned by Android's own setup (tap the welcome screen 6 times after a factory reset),
 *    which downloads the lock app, makes it device owner and hands it the pairing and parent PIN;
 *  - the pairing link, scanned by the other parent's app.
 */
object Setup {
    /** Public download of the lock app that Android's setup fetches. Always the newest release. */
    const val LOCK_APK_URL = "https://github.com/RyanEwen/phonelock/releases/latest/download/lock-release.apk"

    /**
     * URL-safe base64 SHA-256 of the lock app's signing certificate (secrets/phonelock.keystore). Android
     * refuses the download unless it is signed by this key, so a swapped file on the host can't be installed.
     */
    private const val LOCK_SIGNATURE_SHA256 = "iuGxH0rozJJdlt18LabbsCPhxD3R5WTsIs3a7hG5QyM"
    private const val ADMIN_COMPONENT = "home.phonelock/home.phonelock.AdminReceiver"
    /** New phones use the PhoneLock hub when this build has one, else ntfy.sh. */
    private val DEFAULT_SERVER get() = Push.hub.ifEmpty { "https://ntfy.sh" }

    private val rng = SecureRandom()
    private val b64 = Base64.getUrlEncoder().withoutPadding()

    /** A new phone with a random private ntfy topic and a random 32-byte secret. */
    fun newDevice(name: String): Device {
        val topic = "pl-" + randomBase32(24)
        val secret = b64.encodeToString(ByteArray(32).also(rng::nextBytes))
        return Device(DEFAULT_SERVER, topic, secret, name.trim().ifBlank { "Device" })
    }

    /** phonelock://pair?s=…&t=…&k=…&n=…, the link [Hub.parsePairing] reads. */
    fun pairingLink(d: Device): String =
        "phonelock://pair?s=${Uri.encode(d.server)}&t=${d.topic}&k=${d.secret}&n=${Uri.encode(d.name)}"

    /**
     * The Android provisioning QR payload for [d]. [pin] is the parent PIN the phone will ask for to unlock
     * parent mode. [wifiSsid]/[wifiPassword], if given, connect the phone during setup.
     */
    fun provisioningJson(d: Device, pin: String, wifiSsid: String?, wifiPassword: String?): String {
        val extras = JSONObject()
            .put("server", d.server)
            .put("topic", d.topic)
            .put("secret", d.secret)
            .put("name", d.name)
            .put("pin", pin)
        val o = JSONObject()
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME", ADMIN_COMPONENT)
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_PACKAGE_DOWNLOAD_LOCATION", LOCK_APK_URL)
            .put("android.app.extra.PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM", LOCK_SIGNATURE_SHA256)
            // Keep the phone's own apps (dialer, camera…); the lock app hides what isn't allowed.
            .put("android.app.extra.PROVISIONING_LEAVE_ALL_SYSTEM_APPS_ENABLED", true)
            .put("android.app.extra.PROVISIONING_ADMIN_EXTRAS_BUNDLE", extras)
        if (!wifiSsid.isNullOrBlank()) {
            o.put("android.app.extra.PROVISIONING_WIFI_SSID", wifiSsid.trim())
            if (!wifiPassword.isNullOrEmpty()) {
                o.put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "WPA")
                o.put("android.app.extra.PROVISIONING_WIFI_PASSWORD", wifiPassword)
            } else {
                o.put("android.app.extra.PROVISIONING_WIFI_SECURITY_TYPE", "NONE")
            }
        }
        return o.toString()
    }

    private fun randomBase32(n: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        return String(CharArray(n) { alphabet[rng.nextInt(alphabet.length)] })
    }
}
