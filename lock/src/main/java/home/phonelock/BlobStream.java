package home.phonelock;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encrypts large files in pieces so neither app holds a whole file in memory. Format: a sequence of
 * records {@code len[4, big-endian] || iv[12] || AES-256-GCM(piece || tag)}, each piece at most
 * {@link #PIECE} bytes, with AAD {@code <aad>/<index>/<0|1>} where 1 marks the last piece (so pieces
 * can't be reordered, dropped or cut short). Must stay byte-compatible with companion BlobStream.kt.
 */
final class BlobStream {
    static final int PIECE = 1 << 20;
    private static final SecureRandom RNG = new SecureRandom();

    private BlobStream() {}

    /** Encrypted size of a {@code plain}-byte file: per piece, 4 + 12 + 16 bytes on top. */
    static long sealedSize(long plain) {
        long pieces = Math.max(1, (plain + PIECE - 1) / PIECE);
        return plain + pieces * (4 + 12 + 16);
    }

    /** Reads {@code plain} bytes from {@code in} and writes them sealed to {@code out}. */
    static void seal(byte[] key, String aad, InputStream in, long plain, OutputStream out) throws Exception {
        byte[] buf = new byte[PIECE];
        long left = plain;
        int index = 0;
        do {
            int n = (int) Math.min(PIECE, left);
            readFully(in, buf, n);
            left -= n;
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(pieceAad(aad, index, left == 0));
            byte[] ct = c.doFinal(buf, 0, n);
            int len = iv.length + ct.length;
            out.write(new byte[]{(byte) (len >>> 24), (byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
            out.write(iv);
            out.write(ct);
            index++;
        } while (left > 0);
    }

    /**
     * Reads sealed pieces from {@code in} and writes the plaintext to {@code out}, at most {@code maxPlain}
     * bytes. Throws if anything doesn't verify or the last piece never arrives.
     */
    static void open(byte[] key, String aad, InputStream in, OutputStream out, long maxPlain) throws Exception {
        DataInputStream din = new DataInputStream(in);
        long total = 0;
        for (int index = 0; ; index++) {
            int len;
            try {
                len = din.readInt();
            } catch (EOFException e) {
                throw new IOException("File ended early");
            }
            if (len < 12 + 16 || len > PIECE + 12 + 16) throw new IOException("Bad piece");
            byte[] rec = new byte[len];
            din.readFully(rec);
            // Try "not last" first; only the final piece verifies with the last-piece flag.
            byte[] plain = decrypt(key, rec, pieceAad(aad, index, false));
            boolean last = false;
            if (plain == null) {
                plain = decrypt(key, rec, pieceAad(aad, index, true));
                last = true;
            }
            if (plain == null) throw new IOException("The file couldn't be decrypted");
            total += plain.length;
            if (total > maxPlain) throw new IOException("File is too large");
            out.write(plain);
            if (last) return;
        }
    }

    private static byte[] decrypt(byte[] key, byte[] rec, byte[] aad) {
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, rec, 0, 12));
            c.updateAAD(aad);
            return c.doFinal(rec, 12, rec.length - 12);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] pieceAad(String aad, int index, boolean last) {
        return (aad + "/" + index + "/" + (last ? 1 : 0)).getBytes(StandardCharsets.US_ASCII);
    }

    private static void readFully(InputStream in, byte[] buf, int n) throws IOException {
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) throw new EOFException();
            off += r;
        }
    }
}
