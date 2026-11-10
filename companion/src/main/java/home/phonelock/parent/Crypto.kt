package home.phonelock.parent

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM, byte-compatible with lock/.../Crypto.java. Wire: base64url(iv[12] || ct+tag). */
object Crypto {
    private val rng = SecureRandom()
    private val aad = "phonelock/1".toByteArray(Charsets.US_ASCII)
    private val enc = Base64.getUrlEncoder().withoutPadding()
    private val dec = Base64.getUrlDecoder()

    fun key(secret: String): ByteArray =
        dec.decode(secret).also { require(it.size == 32) { "secret must be 32 bytes" } }

    fun seal(key: ByteArray, plaintext: String): String {
        val iv = ByteArray(12).also(rng::nextBytes)
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        c.updateAAD(aad)
        return enc.encodeToString(iv + c.doFinal(plaintext.toByteArray()))
    }

    fun open(key: ByteArray, sealed: String): String? = try {
        val all = dec.decode(sealed.trim())
        if (all.size < 28) null else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, all, 0, 12))
            c.updateAAD(aad)
            String(c.doFinal(all, 12, all.size - 12))
        }
    } catch (e: Exception) {
        null
    }

    fun randomId(): String = enc.encodeToString(ByteArray(9).also(rng::nextBytes))
}
