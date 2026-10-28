package com.openfinance.store;

import java.security.SecureRandom;
import java.util.Base64;

/**
 * Envelope encryption. Each customer has a random 256-bit data key that encrypts their fields; the database stores it
 * only wrapped (AES-256-GCM) under the master key, which lives outside the database: in this prototype the
 * environment variable {@code ETHERM_MASTER_KEY}, in production a KMS or HSM that never releases it. Deleting the
 * wrapped data key (crypto-shredding) leaves every copy of the customer's ciphertext, backups included, unreadable.
 */
public final class KeyRing {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final String id;
    private final byte[] masterKey;

    private KeyRing(String id, byte[] masterKey) {
        if (masterKey.length != 32) {
            throw new IllegalArgumentException("the master key must be 32 bytes (256 bits)");
        }
        this.id = id;
        this.masterKey = masterKey.clone();
    }

    public static KeyRing of(String id, byte[] masterKey) {
        return new KeyRing(id, masterKey);
    }

    /** ETHERM_MASTER_KEY (base64, 32 bytes) and ETHERM_MASTER_KEY_ID (default "local-1"). There is no fallback key. */
    public static KeyRing fromEnvironment() {
        String key = System.getenv("ETHERM_MASTER_KEY");
        if (key == null || key.isBlank()) {
            throw new IllegalStateException("ETHERM_MASTER_KEY is not set; generate one with: openssl rand -base64 32");
        }
        String id = System.getenv().getOrDefault("ETHERM_MASTER_KEY_ID", "local-1");
        return new KeyRing(id, Base64.getDecoder().decode(key.trim()));
    }

    public String id() {
        return id;
    }

    static byte[] newDataKey() {
        byte[] key = new byte[32];
        RANDOM.nextBytes(key);
        return key;
    }

    byte[] wrap(String customerId, byte[] dataKey) {
        return FieldCipher.encrypt(masterKey, dataKey, context(customerId, id));
    }

    byte[] unwrap(String customerId, String kekId, byte[] wrapped) {
        if (!id.equals(kekId)) {
            throw new IllegalStateException("data key for " + customerId + " is wrapped by master key " + kekId + ", not " + id);
        }
        return FieldCipher.decrypt(masterKey, wrapped, context(customerId, kekId));
    }

    private static String context(String customerId, String kekId) {
        return "etherm.data-key|" + kekId + "|" + customerId;
    }
}
