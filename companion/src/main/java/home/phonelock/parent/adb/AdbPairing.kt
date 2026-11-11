package home.phonelock.parent.adb

import android.net.ssl.SSLSockets
import java.io.DataInputStream
import java.io.IOException
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Android Wireless debugging's "Pair device with pairing code", from the host side (AOSP
 * packages/modules/adb/pairing_connection and pairing_auth):
 *
 *  1. TLS 1.3 to the pairing port, each side with a self-signed certificate.
 *  2. SPAKE2 with password = the 6-digit code followed by 64 bytes exported from that TLS session, so the
 *     exchange is tied to this connection.
 *  3. Each side sends its PeerInfo (8192 bytes; ours carries our adb public key) encrypted with AES-128-GCM
 *     under a key derived from the SPAKE2 result. If the codes differ, the device can't decrypt ours and
 *     pairing fails.
 *
 * Afterwards the device trusts [AdbIdentity]'s key for normal Wireless debugging connections.
 */
internal object AdbPairing {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val PACKET_VERSION = 1
    private const val TYPE_SPAKE2_MSG = 0
    private const val TYPE_PEER_INFO = 1
    private const val PEER_INFO_SIZE = 8192
    private const val MAX_PAYLOAD = PEER_INFO_SIZE * 2
    private const val PEER_RSA_PUB_KEY = 0
    // NUL included: adb passes sizeof() of these C strings.
    private val CLIENT_NAME = "adb pair client\u0000".toByteArray()
    private val SERVER_NAME = "adb pair server\u0000".toByteArray()
    private const val EXPORT_LABEL = "adb-label\u0000"
    private const val EXPORT_SIZE = 64
    private val HKDF_INFO = "adb pairing_auth aes-128-gcm key".toByteArray()

    /** Thrown when the device rejects the pairing (almost always a wrong or expired code). */
    class PairingRefused(message: String) : IOException(message)

    /**
     * Pairs with the device at [host]:[port] using [code]. [name] labels this phone in the device's list of
     * paired devices. Blocks; throws on failure.
     */
    fun pair(identity: AdbIdentity, host: String, port: Int, code: String, name: String) {
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
            plain.soTimeout = READ_TIMEOUT_MS
            identity.startTls(plain, host, port).use { tls ->
                val input = DataInputStream(tls.inputStream)
                val output = tls.outputStream

                // 2. SPAKE2, bound to this TLS session.
                val exported = SSLSockets.exportKeyingMaterial(tls, EXPORT_LABEL, null, EXPORT_SIZE)
                    ?: throw IOException("Couldn't secure the pairing connection")
                val spake = Spake2(alice = true, myName = CLIENT_NAME, theirName = SERVER_NAME)
                writePacket(output, TYPE_SPAKE2_MSG, spake.generateMessage(code.toByteArray() + exported))
                val theirMsg = readPacket(input, TYPE_SPAKE2_MSG)
                val keyMaterial = spake.processMessage(theirMsg) ?: throw PairingRefused("The device sent an invalid pairing message")
                val cipher = PairingCipher(hkdf(keyMaterial, HKDF_INFO, 16))

                // 3. Exchange PeerInfo. Ours: type RSA public key, then the key as text, zero-padded.
                val info = ByteArray(PEER_INFO_SIZE)
                info[0] = PEER_RSA_PUB_KEY.toByte()
                val key = identity.adbPublicKey(name).toByteArray()
                System.arraycopy(key, 0, info, 1, minOf(key.size, PEER_INFO_SIZE - 2))
                writePacket(output, TYPE_PEER_INFO, cipher.encrypt(info))
                val theirs = try {
                    readPacket(input, TYPE_PEER_INFO)
                } catch (e: java.net.SocketTimeoutException) {
                    throw IOException("The device stopped answering during pairing", e)
                } catch (e: IOException) {
                    // The device hangs up when it can't decrypt our PeerInfo: the codes didn't match.
                    throw PairingRefused("Wrong pairing code, or it expired")
                }
                cipher.decrypt(theirs) ?: throw PairingRefused("Wrong pairing code, or it expired")
            }
        }
    }

    /** Header: version (1 byte), type (1 byte), payload length (4 bytes, big-endian); then the payload. */
    private fun writePacket(out: OutputStream, type: Int, payload: ByteArray) {
        val header = ByteBuffer.allocate(6).order(ByteOrder.BIG_ENDIAN)
            .put(PACKET_VERSION.toByte()).put(type.toByte()).putInt(payload.size).array()
        out.write(header + payload)
        out.flush()
    }

    private fun readPacket(input: DataInputStream, expectType: Int): ByteArray {
        val version = input.readUnsignedByte()
        val type = input.readUnsignedByte()
        val size = input.readInt()
        if (version != PACKET_VERSION) throw IOException("Unsupported pairing version $version")
        if (type != expectType) throw IOException("Unexpected pairing message")
        if (size <= 0 || size > MAX_PAYLOAD) throw IOException("Bad pairing message size")
        return ByteArray(size).also(input::readFully)
    }

    /**
     * AES-128-GCM with the nonce being a per-direction message counter (8 bytes little-endian, then zeros),
     * as adb's aes_128_gcm.cpp does.
     */
    private class PairingCipher(private val key: ByteArray) {
        private var encSeq = 0L
        private var decSeq = 0L

        fun encrypt(plain: ByteArray): ByteArray = run(Cipher.ENCRYPT_MODE, plain, encSeq++)!!

        fun decrypt(sealed: ByteArray): ByteArray? = runCatching { run(Cipher.DECRYPT_MODE, sealed, decSeq++) }.getOrNull()

        private fun run(mode: Int, data: ByteArray, seq: Long): ByteArray? {
            val nonce = ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(seq).array()
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
            return c.doFinal(data)
        }
    }

    /** HKDF-SHA256 (RFC 5869) with no salt, producing [length] bytes. */
    private fun hkdf(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val extract = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(ByteArray(32), "HmacSHA256")) }.doFinal(ikm)
        val expand = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(extract, "HmacSHA256")) }
        var t = ByteArray(0)
        val out = ByteArray(length)
        var filled = 0
        var counter = 1
        while (filled < length) {
            expand.update(t)
            expand.update(info)
            expand.update(counter.toByte())
            t = expand.doFinal()
            val n = minOf(t.size, length - filled)
            System.arraycopy(t, 0, out, filled, n)
            filled += n
            counter++
        }
        return out
    }
}
