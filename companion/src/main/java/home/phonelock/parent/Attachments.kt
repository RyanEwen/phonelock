package home.phonelock.parent

import java.net.HttpURLConnection
import java.net.URL

/** Encrypted ntfy attachments from a paired phone (screenshots, icon packs). See PROTOCOL.md. */
object Attachments {
    /** Screenshots and icon packs. */
    private const val MAX_BYTES = 4L * 1024 * 1024
    /** Files from file management (the hub takes up to 50 MB). */
    const val MAX_FILE_BYTES = 50L * 1024 * 1024

    /**
     * Downloads and decrypts the attachment of sealed message [msgId] from [d]. Returns null if the URL isn't
     * on the phone's own server (the URL comes from the unencrypted ntfy envelope) or the blob doesn't
     * decrypt with the phone's key and the message's AAD. Blocks; call off the main thread.
     */
    fun fetch(d: Device, url: String, msgId: String, maxBytes: Long = MAX_BYTES): ByteArray? {
        if (!url.startsWith(d.server + "/")) return null
        val h = URL(url).openConnection() as HttpURLConnection
        h.connectTimeout = 15_000
        h.readTimeout = 30_000
        val enc = try {
            // ntfy.sh caps attachments at 2 MB; refuse anything much larger rather than filling memory.
            if (h.contentLengthLong > maxBytes + 64) return null
            h.inputStream.use { it.readNBytes(maxBytes.toInt() + 65) }.takeIf { it.size <= maxBytes + 64 } ?: return null
        } finally {
            h.disconnect()
        }
        return Crypto.openBytes(Crypto.key(d.secret), enc, "phonelock/blob/$msgId")
    }
}
