package home.phonelock.parent

import android.net.Uri
import java.security.SecureRandom
import java.util.Base64

/**
 * Creates the pairing for a new child's device (used by phone-to-phone setup, [ChildSetup]) and the pairing
 * link another parent's app scans to add it too.
 */
object Setup {
    /** Public download of the lock app that setup installs. Always the newest release. */
    const val LOCK_APK_URL = "https://github.com/RyanEwen/phonelock/releases/latest/download/lock-release.apk"

    /**
     * URL-safe base64 SHA-256 of the lock app's signing certificate (secrets/phonelock.keystore). Setup
     * refuses the download unless it is signed by this key, so a swapped file on the host can't be installed.
     */
    internal const val LOCK_SIGNATURE_SHA256 = "iuGxH0rozJJdlt18LabbsCPhxD3R5WTsIs3a7hG5QyM"
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

    private fun randomBase32(n: Int): String {
        val alphabet = "abcdefghijklmnopqrstuvwxyz234567"
        return String(CharArray(n) { alphabet[rng.nextInt(alphabet.length)] })
    }
}
