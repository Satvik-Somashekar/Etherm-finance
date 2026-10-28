package com.openfinance.store;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * AES-256-GCM for one field. The output is a version byte, a random 12-byte nonce and the ciphertext with its tag.
 * The associated data names where the value belongs (customer, record, column), so a ciphertext copied into another
 * row or column fails to decrypt instead of showing the wrong customer's amount.
 */
public final class FieldCipher {

    private static final byte VERSION = 1;
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private FieldCipher() {
    }

    public static byte[] encrypt(byte[] key, String plaintext, String context) {
        return encrypt(key, plaintext.getBytes(StandardCharsets.UTF_8), context);
    }

    static byte[] encrypt(byte[] key, byte[] plaintext, String context) {
        byte[] nonce = new byte[NONCE];
        RANDOM.nextBytes(nonce);
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, nonce));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] sealed = c.doFinal(plaintext);
            byte[] out = new byte[1 + NONCE + sealed.length];
            out[0] = VERSION;
            System.arraycopy(nonce, 0, out, 1, NONCE);
            System.arraycopy(sealed, 0, out, 1 + NONCE, sealed.length);
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    public static String decryptText(byte[] key, byte[] sealed, String context) {
        return new String(decrypt(key, sealed, context), StandardCharsets.UTF_8);
    }

    static byte[] decrypt(byte[] key, byte[] sealed, String context) {
        if (sealed == null || sealed.length < 1 + NONCE + TAG_BITS / 8 || sealed[0] != VERSION) {
            throw new TamperedException("not a field ciphertext");
        }
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(sealed, 1, 1 + NONCE)));
            c.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return c.doFinal(sealed, 1 + NONCE, sealed.length - 1 - NONCE);
        } catch (AEADBadTagException e) {
            throw new TamperedException("the value was altered, moved, or sealed under another key");
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("AES-GCM is not available", e);
        }
    }

    /** A ciphertext that does not open under this key and context. */
    public static final class TamperedException extends RuntimeException {
        TamperedException(String message) {
            super(message);
        }
    }
}
