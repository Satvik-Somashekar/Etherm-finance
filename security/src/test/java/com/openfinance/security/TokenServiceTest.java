package com.openfinance.security;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TokenServiceTest {

    private static final byte[] KEY = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.US_ASCII);
    private final Clock t0 = Clock.fixed(Instant.parse("2026-10-28T10:00:00Z"), ZoneOffset.UTC);

    @Test
    void aTokenNamesTheCustomerItWasIssuedTo() {
        TokenService tokens = new TokenService(KEY, t0);
        var claims = tokens.verify(tokens.issueCustomer("user001@etherm-aa", Duration.ofMinutes(60))).orElseThrow();
        assertThat(claims.subject()).isEqualTo("user001@etherm-aa");
        assertThat(claims.service()).isFalse();
        assertThat(tokens.verify(tokens.issueService("rag-service", Duration.ofSeconds(60))).orElseThrow().service()).isTrue();
    }

    @Test
    void anExpiredTokenIsRefused() {
        String token = new TokenService(KEY, t0).issueCustomer("user001@etherm-aa", Duration.ofMinutes(60));
        Clock later = Clock.offset(t0, Duration.ofMinutes(60));
        assertThat(new TokenService(KEY, later).verify(token)).isEmpty();
    }

    @Test
    void aTokenSignedWithAnotherKeyIsRefused() {
        String token = new TokenService(KEY, t0).issueCustomer("user001@etherm-aa", Duration.ofMinutes(60));
        byte[] other = KEY.clone();
        other[0] ^= 1;
        assertThat(new TokenService(other, t0).verify(token)).isEmpty();
    }

    @Test
    void changingTheCustomerBreaksTheSignature() {
        TokenService tokens = new TokenService(KEY, t0);
        String token = tokens.issueCustomer("user001@etherm-aa", Duration.ofMinutes(60));
        String claims = new String(Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))), StandardCharsets.UTF_8);
        String forged = Base64.getUrlEncoder().withoutPadding().encodeToString(
                claims.replace("user001", "user002").getBytes(StandardCharsets.UTF_8)) + token.substring(token.indexOf('.'));
        assertThat(tokens.verify(forged)).isEmpty();
    }

    @Test
    void malformedTokensAreRefusedNotThrown() {
        TokenService tokens = new TokenService(KEY, t0);
        for (String junk : new String[]{null, "", ".", "a.b.c", "eyJzdWIiOiJ4In0.", "eyJzdWIiOiJ4In0.!!!", "x".repeat(5000)}) {
            assertThat(tokens.verify(junk)).as(String.valueOf(junk)).isEmpty();
        }
    }

    @Test
    void aCustomerCannotBeIssuedAServiceIdentityAndShortKeysAreRejected() {
        TokenService tokens = new TokenService(KEY, t0);
        assertThatThrownBy(() -> tokens.issueCustomer("service:rag-service", Duration.ofMinutes(1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TokenService(new byte[16], t0)).isInstanceOf(IllegalArgumentException.class);
    }
}
