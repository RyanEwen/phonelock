package home.phonelock.parent.adb

import java.math.BigInteger

/**
 * The edwards25519 group, just enough for SPAKE2 ([Spake2]): point addition, scalar multiplication and the
 * standard 32-byte encoding, with plain BigInteger arithmetic in extended coordinates (a = -1).
 *
 * Not constant-time. That is acceptable here: it runs on the parent's phone for a one-off pairing, with a
 * secret that only lives for the few seconds Android's pairing dialog is open, and a timing attack would
 * need precise measurements of this phone's computation from the network during those seconds.
 */
internal object Ed25519 {
    /** The field prime 2^255 - 19. */
    val P: BigInteger = BigInteger.TWO.pow(255).subtract(BigInteger.valueOf(19))
    /** Order of the prime-order subgroup: 2^252 + 27742317777372353535851937790883648493. */
    val L: BigInteger = BigInteger.TWO.pow(252).add(BigInteger("27742317777372353535851937790883648493"))
    /** Curve constant d = -121665/121666. */
    private val D: BigInteger = BigInteger.valueOf(-121665).multiply(inv(BigInteger.valueOf(121666))).mod(P)
    private val D2: BigInteger = D.shiftLeft(1).mod(P)
    /** A square root of -1, used when decoding points. */
    private val SQRT_M1: BigInteger = BigInteger.TWO.modPow(P.subtract(BigInteger.ONE).shiftRight(2), P)

    /** A point in extended coordinates: x = X/Z, y = Y/Z, x*y = T/Z. */
    class Point(val x: BigInteger, val y: BigInteger, val z: BigInteger, val t: BigInteger)

    val IDENTITY = Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO)

    /** The standard base point (y = 4/5, x even). */
    val BASE: Point = decode(hex("5866666666666666666666666666666666666666666666666666666666666666"))!!

    private fun inv(a: BigInteger) = a.modPow(P.subtract(BigInteger.TWO), P)

    /** p + q (the unified formula for a = -1, which also doubles). */
    fun add(p: Point, q: Point): Point {
        val a = p.y.subtract(p.x).multiply(q.y.subtract(q.x)).mod(P)
        val b = p.y.add(p.x).multiply(q.y.add(q.x)).mod(P)
        val c = p.t.multiply(D2).multiply(q.t).mod(P)
        val d = p.z.shiftLeft(1).multiply(q.z).mod(P)
        val e = b.subtract(a)
        val f = d.subtract(c)
        val g = d.add(c)
        val h = b.add(a)
        return Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P))
    }

    fun negate(p: Point) = Point(P.subtract(p.x).mod(P), p.y, p.z, P.subtract(p.t).mod(P))

    fun subtract(p: Point, q: Point) = add(p, negate(q))

    /** k·p for any non-negative k (left-to-right double and add). */
    fun multiply(p: Point, k: BigInteger): Point {
        require(k.signum() >= 0)
        var r = IDENTITY
        for (i in k.bitLength() - 1 downTo 0) {
            r = add(r, r)
            if (k.testBit(i)) r = add(r, p)
        }
        return r
    }

    /** The 32-byte encoding: y little-endian, with the top bit set when x is odd. */
    fun encode(p: Point): ByteArray {
        val zi = inv(p.z)
        val x = p.x.multiply(zi).mod(P)
        val y = p.y.multiply(zi).mod(P)
        val out = toLittleEndian(y, 32)
        if (x.testBit(0)) out[31] = (out[31].toInt() or 0x80).toByte()
        return out
    }

    /**
     * Decodes a point, or null if it isn't on the curve. Like BoringSSL's decoder (which the other end of an
     * adb pairing uses), the top bit is the sign of x and the rest is y, read modulo p.
     */
    fun decode(s: ByteArray): Point? {
        if (s.size != 32) return null
        val sign = (s[31].toInt() ushr 7) and 1
        val bytes = s.copyOf().also { it[31] = (it[31].toInt() and 0x7f).toByte() }
        val y = fromLittleEndian(bytes).mod(P)
        val y2 = y.multiply(y).mod(P)
        val u = y2.subtract(BigInteger.ONE).mod(P)
        val v = D.multiply(y2).add(BigInteger.ONE).mod(P)

        // x = u·v³·(u·v⁷)^((p-5)/8), then fix up by √-1 if that squared to -u instead of u.
        val v3 = v.multiply(v).multiply(v).mod(P)
        val uv7 = u.multiply(v3).multiply(v3).multiply(v).mod(P)
        var x = u.multiply(v3).multiply(uv7.modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P)
        val vxx = v.multiply(x).multiply(x).mod(P)
        if (vxx != u) {
            if (vxx != P.subtract(u).mod(P)) return null
            x = x.multiply(SQRT_M1).mod(P)
        }
        if ((if (x.testBit(0)) 1 else 0) != sign) x = P.subtract(x).mod(P)
        return Point(x, y, BigInteger.ONE, x.multiply(y).mod(P))
    }

    // ---------- byte helpers ----------

    /** Reads an unsigned little-endian integer. */
    fun fromLittleEndian(b: ByteArray): BigInteger = BigInteger(1, b.reversedArray())

    /** Writes [n] as [size] little-endian bytes. */
    fun toLittleEndian(n: BigInteger, size: Int): ByteArray {
        val be = n.toByteArray()
        val out = ByteArray(size)
        for (i in be.indices) {
            val pos = be.size - 1 - i
            if (i < size) out[i] = be[pos]
        }
        return out
    }

    fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
