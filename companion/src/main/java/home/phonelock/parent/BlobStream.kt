package home.phonelock.parent

import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Pieced file encryption, byte-compatible with lock/.../BlobStream.java: records
 * `len[4] || iv[12] || AES-GCM(piece || tag)`, pieces up to [PIECE] bytes, AAD `<aad>/<index>/<0|1>` with
 * 1 on the last piece. Neither side holds a whole file in memory.
 */
object BlobStream {
    const val PIECE = 1 shl 20
    private val rng = SecureRandom()

    fun sealedSize(plain: Long): Long = plain + maxOf(1L, (plain + PIECE - 1) / PIECE) * (4 + 12 + 16)

    fun seal(key: ByteArray, aad: String, input: InputStream, plain: Long, out: OutputStream) {
        val buf = ByteArray(PIECE)
        var left = plain
        var index = 0
        do {
            val n = minOf(PIECE.toLong(), left).toInt()
            var off = 0
            while (off < n) {
                val r = input.read(buf, off, n - off)
                if (r < 0) throw EOFException()
                off += r
            }
            left -= n
            val iv = ByteArray(12).also(rng::nextBytes)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
            c.updateAAD(pieceAad(aad, index, left == 0L))
            val ct = c.doFinal(buf, 0, n)
            val len = iv.size + ct.size
            out.write(byteArrayOf((len ushr 24).toByte(), (len ushr 16).toByte(), (len ushr 8).toByte(), len.toByte()))
            out.write(iv)
            out.write(ct)
            index++
        } while (left > 0)
    }

    fun open(key: ByteArray, aad: String, input: InputStream, out: OutputStream, maxPlain: Long) {
        val din = DataInputStream(input)
        var total = 0L
        var index = 0
        while (true) {
            val len = try { din.readInt() } catch (e: EOFException) { throw IOException("File ended early") }
            if (len < 28 || len > PIECE + 28) throw IOException("Bad piece")
            val rec = ByteArray(len).also { din.readFully(it) }
            var last = false
            val plain = decrypt(key, rec, pieceAad(aad, index, false))
                ?: decrypt(key, rec, pieceAad(aad, index, true))?.also { last = true }
                ?: throw IOException("The file couldn't be decrypted")
            total += plain.size
            if (total > maxPlain) throw IOException("File is too large")
            out.write(plain)
            if (last) return
            index++
        }
    }

    private fun decrypt(key: ByteArray, rec: ByteArray, aad: ByteArray): ByteArray? = try {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, rec, 0, 12))
        c.updateAAD(aad)
        c.doFinal(rec, 12, rec.size - 12)
    } catch (e: Exception) {
        null
    }

    private fun pieceAad(aad: String, index: Int, last: Boolean) = "$aad/$index/${if (last) 1 else 0}".toByteArray(Charsets.US_ASCII)
}
