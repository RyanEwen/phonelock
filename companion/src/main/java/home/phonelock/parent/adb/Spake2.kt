package home.phonelock.parent.adb

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * SPAKE2 over edwards25519, exactly as BoringSSL implements it (crypto/curve25519/spake25519.cc), which is
 * what Android's Wireless debugging uses to turn the 6-digit pairing code into a shared key. Ported from
 * BoringSSL (Copyright 2016 The BoringSSL Authors, Apache License 2.0); see play/setup-helper.md.
 *
 * Both sides mask an ephemeral public key with the password: A sends x·B + pw·M, B sends y·B + pw·N, and each
 * removes the other's mask to reach the same x·y·B. A wrong password, or any detail that differs from
 * BoringSSL, gives a different key, and the pairing then fails when the peer can't decrypt (it fails
 * closed). One context does one exchange: [generateMessage], then [processMessage].
 *
 * @param alice true for the pairing client ("adb pair client"), false for the server role.
 */
internal class Spake2(private val alice: Boolean, private val myName: ByteArray, private val theirName: ByteArray,
                      private val random: SecureRandom = SecureRandom()) {
    private var privateKey: BigInteger? = null
    private var passwordScalar: BigInteger = BigInteger.ZERO
    private var passwordHash = ByteArray(0)
    private var myMsg = ByteArray(0)

    /** Our 32-byte message for [password]. */
    fun generateMessage(password: ByteArray): ByteArray {
        check(privateKey == null) { "message already generated" }

        // Ephemeral key: 64 random bytes reduced mod l, times the cofactor 8 so the peer's small-order
        // components cancel out later.
        val wide = ByteArray(64).also(random::nextBytes)
        val x = Ed25519.fromLittleEndian(wide).mod(Ed25519.L).shiftLeft(3)
        privateKey = x
        val publicKey = Ed25519.multiply(Ed25519.BASE, x)

        // pw = SHA-512(password) mod l, then made a multiple of 8 by adding l, 2l and 4l as needed (BoringSSL's
        // fix for an earlier omission; the other side does the same, so it must be reproduced exactly).
        passwordHash = MessageDigest.getInstance("SHA-512").digest(password)
        var pw = Ed25519.fromLittleEndian(passwordHash).mod(Ed25519.L)
        for (shift in 0..2) {
            if (pw.testBit(shift)) pw = pw.add(Ed25519.L.shiftLeft(shift))
        }
        passwordScalar = pw

        val mask = Ed25519.multiply(if (alice) M else N, pw)
        myMsg = Ed25519.encode(Ed25519.add(publicKey, mask))
        return myMsg.copyOf()
    }

    /** The 64-byte shared key from the peer's message, or null if it isn't a valid point. */
    fun processMessage(theirMsg: ByteArray): ByteArray? {
        val x = checkNotNull(privateKey) { "generateMessage first" }
        if (theirMsg.size != 32) return null
        val qStar = Ed25519.decode(theirMsg) ?: return null

        // Remove their mask (they used N if we are A, M if we are B), then multiply by our ephemeral key.
        val q = Ed25519.subtract(qStar, Ed25519.multiply(if (alice) N else M, passwordScalar))
        val shared = Ed25519.encode(Ed25519.multiply(q, x))

        // Transcript hash, always in A-then-B order, each part prefixed with its 8-byte little-endian length.
        val sha = MessageDigest.getInstance("SHA-512")
        fun put(b: ByteArray) {
            sha.update(Ed25519.toLittleEndian(BigInteger.valueOf(b.size.toLong()), 8))
            sha.update(b)
        }
        if (alice) {
            put(myName); put(theirName); put(myMsg); put(theirMsg)
        } else {
            put(theirName); put(myName); put(theirMsg); put(myMsg)
        }
        put(shared)
        put(passwordHash)
        return sha.digest()
    }

    companion object {
        // BoringSSL's fixed points, from hashing "edwards25519 point generation seed (M)" and "(N)".
        private val M = Ed25519.decode(Ed25519.hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"))!!
        private val N = Ed25519.decode(Ed25519.hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"))!!
    }
}
