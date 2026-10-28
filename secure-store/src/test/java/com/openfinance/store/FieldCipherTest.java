package com.openfinance.store;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FieldCipherTest {

    private static byte[] key() {
        byte[] k = new byte[32];
        new SecureRandom().nextBytes(k);
        return k;
    }

    @Test
    void roundTripsAndHidesThePlaintext() {
        byte[] key = key();
        byte[] sealed = FieldCipher.encrypt(key, "25000.00", "ctx");
        assertThat(new String(sealed, StandardCharsets.ISO_8859_1)).doesNotContain("25000");
        assertThat(FieldCipher.decryptText(key, sealed, "ctx")).isEqualTo("25000.00");
        // A fresh nonce each time: the same amount never produces the same ciphertext.
        assertThat(FieldCipher.encrypt(key, "25000.00", "ctx")).isNotEqualTo(sealed);
    }

    @Test
    void refusesAlteredMovedOrForeignCiphertext() {
        byte[] key = key();
        byte[] sealed = FieldCipher.encrypt(key, "25000.00", "row-1|amount");
        byte[] flipped = sealed.clone();
        flipped[flipped.length - 1] ^= 1;
        assertThatThrownBy(() -> FieldCipher.decryptText(key, flipped, "row-1|amount")).isInstanceOf(FieldCipher.TamperedException.class);
        assertThatThrownBy(() -> FieldCipher.decryptText(key, sealed, "row-2|amount")).isInstanceOf(FieldCipher.TamperedException.class);
        assertThatThrownBy(() -> FieldCipher.decryptText(key(), sealed, "row-1|amount")).isInstanceOf(FieldCipher.TamperedException.class);
        assertThatThrownBy(() -> FieldCipher.decryptText(key, new byte[3], "row-1|amount")).isInstanceOf(FieldCipher.TamperedException.class);
    }

    @Test
    void dataKeysOnlyUnwrapForTheirCustomerUnderTheirMasterKey() {
        KeyRing ring = KeyRing.of("k1", key());
        byte[] dataKey = KeyRing.newDataKey();
        byte[] wrapped = ring.wrap("user001", dataKey);
        assertThat(ring.unwrap("user001", "k1", wrapped)).isEqualTo(dataKey);
        assertThatThrownBy(() -> ring.unwrap("user002", "k1", wrapped)).isInstanceOf(FieldCipher.TamperedException.class);
        assertThatThrownBy(() -> KeyRing.of("k1", key()).unwrap("user001", "k1", wrapped)).isInstanceOf(FieldCipher.TamperedException.class);
        assertThatThrownBy(() -> ring.unwrap("user001", "k0", wrapped)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> KeyRing.of("short", new byte[16])).isInstanceOf(IllegalArgumentException.class);
    }
}
