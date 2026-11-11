package home.phonelock;

import android.util.Base64;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with the shared pairing secret. GCM authenticates as well as encrypts, so a message that
 * decrypts was written by someone holding the secret. Wire format: base64url(iv[12] || ciphertext+tag).
 * Must stay byte-compatible with companion/.../Crypto.kt.
 */
public final class Crypto {
    private static final SecureRandom RNG = new SecureRandom();
    private Crypto() {}

    static byte[] key(String secretB64) {
        byte[] k = Base64.decode(secretB64, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        if (k.length != 32) throw new IllegalArgumentException("secret must be 32 bytes");
        return k;
    }

    static String seal(byte[] key, String plaintext) throws Exception {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD("phonelock/1".getBytes(StandardCharsets.US_ASCII));
        byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return Base64.encodeToString(out, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }

    /**
     * Encrypts a binary blob (a screenshot) for an ntfy attachment: raw iv[12] || ciphertext+tag, no base64.
     * {@code aad} binds the blob to the sealed message that announces it, so a blob can't be swapped
     * for another one under the same key.
     */
    static byte[] sealBytes(byte[] key, byte[] data, String aad) throws Exception {
        byte[] iv = new byte[12];
        RNG.nextBytes(iv);
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
        c.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
        byte[] ct = c.doFinal(data);
        byte[] out = new byte[iv.length + ct.length];
        System.arraycopy(iv, 0, out, 0, iv.length);
        System.arraycopy(ct, 0, out, iv.length, ct.length);
        return out;
    }

    /** Decrypts a blob from {@link #sealBytes}; null if it wasn't sealed with this key and AAD. */
    static byte[] openBytes(byte[] key, byte[] data, String aad) {
        try {
            if (data.length < 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, data, 0, 12));
            c.updateAAD(aad.getBytes(StandardCharsets.US_ASCII));
            return c.doFinal(data, 12, data.length - 12);
        } catch (Exception e) {
            return null;
        }
    }

    /** Returns null if the message wasn't sealed with this key. */
    static String open(byte[] key, String sealed) {
        try {
            byte[] in = Base64.decode(sealed.trim(), Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
            if (in.length < 12 + 16) return null;
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(128, in, 0, 12));
            c.updateAAD("phonelock/1".getBytes(StandardCharsets.US_ASCII));
            return new String(c.doFinal(in, 12, in.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    static String randomId() {
        byte[] b = new byte[9];
        RNG.nextBytes(b);
        return Base64.encodeToString(b, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
    }
}
