package home.phonelock.parent.adb

import android.content.Context
import java.io.ByteArrayOutputStream
import java.io.File
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509TrustManager

/**
 * This phone's adb identity: a 2048-bit RSA key, as the adb host tools use, with a self-signed certificate for
 * the TLS that Wireless debugging requires. Pairing registers the public key with the child's device; later
 * connections are accepted because the TLS client certificate carries that key.
 *
 * Kept in app-private storage, so a later "Fix setup" on the same device may not need pairing again.
 */
internal class AdbIdentity private constructor(val privateKey: PrivateKey, val certificate: X509Certificate) {
    private val publicKey get() = certificate.publicKey as RSAPublicKey

    /**
     * The public key as adb writes it in adb_keys: base64 of Android's RSA key structure, then a name.
     * Sent to the device during pairing.
     */
    fun adbPublicKey(name: String): String {
        val n = publicKey.modulus
        val words = 2048 / 32
        val r32 = BigInteger.ONE.shiftLeft(32)
        val n0inv = r32.subtract(n.mod(r32).modInverse(r32))
        val rr = BigInteger.ONE.shiftLeft(2048).modPow(BigInteger.TWO, n)
        val out = ByteArrayOutputStream()
        out.write(le32(words))
        out.write(le32(n0inv.toInt()))
        out.write(Ed25519.toLittleEndian(n, 256))
        out.write(Ed25519.toLittleEndian(rr, 256))
        out.write(le32(publicKey.publicExponent.toInt()))
        return Base64.getEncoder().encodeToString(out.toByteArray()) + " " + name
    }

    /** A TLS 1.3 socket factory that presents this identity and accepts the device's self-signed certificate. */
    fun socketFactory(): SSLSocketFactory {
        val ctx = SSLContext.getInstance("TLSv1.3")
        ctx.init(arrayOf(KeyManager()), arrayOf(SelfSignedTrust), SecureRandom())
        return ctx.socketFactory
    }

    /** Upgrades [plain] to TLS 1.3 as the client and completes the handshake. */
    fun startTls(plain: Socket, host: String, port: Int): SSLSocket {
        val s = socketFactory().createSocket(plain, host, port, true) as SSLSocket
        // The wrapping socket may not inherit the plain one's read timeout: set it again.
        s.soTimeout = plain.soTimeout
        s.enabledProtocols = arrayOf("TLSv1.3")
        s.useClientMode = true
        s.startHandshake()
        return s
    }

    /** Always offers this identity, whatever certificate authorities the device lists. */
    private inner class KeyManager : X509ExtendedKeyManager() {
        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS
        override fun getCertificateChain(alias: String?) = arrayOf(certificate)
        override fun getPrivateKey(alias: String?) = privateKey
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)
        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?) = null
        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?) = null
    }

    /**
     * Wireless debugging devices present a self-signed certificate that no authority vouches for, and the adb
     * host tools accept it. Pairing is still protected: SPAKE2 is bound to that TLS session's exported key, so
     * a man in the middle can't complete it without the code. Later connections are not: like stock adb, we
     * don't check that the device is the one we paired with, so someone able to intercept traffic on the
     * local Wi-Fi during setup could impersonate it. We do insist on a proper self-signed certificate.
     */
    private object SelfSignedTrust : X509TrustManager {
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("No certificate")
            cert.verify(cert.publicKey)
        }

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw java.security.cert.CertificateException("Not a server")

        override fun getAcceptedIssuers() = arrayOf<X509Certificate>()
    }

    companion object {
        private const val ALIAS = "adb"

        /** Loads this phone's identity from [c]'s private files, creating it the first time. */
        @Synchronized
        fun load(c: Context): AdbIdentity {
            val keyFile = File(c.filesDir, "adbkey.pk8")
            val certFile = File(c.filesDir, "adbkey.crt")
            if (keyFile.isFile && certFile.isFile) {
                runCatching {
                    val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
                    val cert = certFile.inputStream().use {
                        CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
                    }
                    // Only a matching pair is usable (a crash while saving could leave a new key and an old cert).
                    val pub = cert.publicKey as RSAPublicKey
                    if ((key as java.security.interfaces.RSAPrivateCrtKey).modulus == pub.modulus) return AdbIdentity(key, cert)
                }
            }
            val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
            val cert = selfSigned(pair.private, pair.public.encoded)
            writeAtomically(keyFile, pair.private.encoded)
            writeAtomically(certFile, cert.encoded)
            return AdbIdentity(pair.private, cert)
        }

        /**
         * A minimal X.509 v3 certificate for [publicKeyInfo] (DER SubjectPublicKeyInfo), signed by its own key
         * with SHA-256/RSA, valid for 10 years. Android has no certificate builder, so it's encoded by hand.
         */
        private fun selfSigned(key: PrivateKey, publicKeyInfo: ByteArray): X509Certificate {
            val sha256WithRsa = seq(oid(byteArrayOf(0x2a, 0x86.toByte(), 0x48, 0x86.toByte(), 0xf7.toByte(), 0x0d, 0x01, 0x01, 0x0b)), byteArrayOf(0x05, 0x00))
            val name = seq(set(seq(oid(byteArrayOf(0x55, 0x04, 0x03)), tlv(0x0c, "PhoneLock".toByteArray()))))
            val now = System.currentTimeMillis()
            val validity = seq(utcTime(now - 24 * 3600_000L), utcTime(now + 10 * 365L * 24 * 3600_000L))
            val serial = BigInteger(63, SecureRandom()).add(BigInteger.ONE)
            val tbs = seq(
                tlv(0xa0, tlv(0x02, byteArrayOf(2))), // version 3
                tlv(0x02, serial.toByteArray()),
                sha256WithRsa, name, validity, name, publicKeyInfo,
            )
            val sig = Signature.getInstance("SHA256withRSA").run {
                initSign(key)
                update(tbs)
                sign()
            }
            val der = seq(tbs, sha256WithRsa, tlv(0x03, byteArrayOf(0) + sig))
            return CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate
        }

        /** Writes through a temporary file, so a crash can't leave a half-written file. */
        private fun writeAtomically(f: File, bytes: ByteArray) {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(f)) throw java.io.IOException("Couldn't save ${f.name}")
        }

        // ---------- tiny DER encoder ----------

        private fun tlv(tag: Int, body: ByteArray): ByteArray {
            val len = when {
                body.size < 0x80 -> byteArrayOf(body.size.toByte())
                body.size < 0x100 -> byteArrayOf(0x81.toByte(), body.size.toByte())
                else -> byteArrayOf(0x82.toByte(), (body.size shr 8).toByte(), body.size.toByte())
            }
            return byteArrayOf(tag.toByte()) + len + body
        }

        private fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { a, b -> a + b })
        private fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { a, b -> a + b })
        private fun oid(encoded: ByteArray) = tlv(0x06, encoded)

        private fun utcTime(ms: Long): ByteArray {
            val f = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return tlv(0x17, f.format(Date(ms)).toByteArray())
        }

        private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }
}
