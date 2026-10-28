package com.openfinance.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Issues and checks short-lived signed tokens: {@code base64url(claims).base64url(HMAC-SHA256)}. A customer token
 * names the customer it was issued to; a service token names the calling service and lets one Etherm service call
 * another. Every service holds the same key ({@code ETHERM_AUTH_KEY}), so any of them can check a token; none of
 * them stores sessions.
 *
 * <p>The format is deliberately small and fixed (one algorithm, no header to choose another) so there is no
 * algorithm-confusion or "none" case to get wrong.
 */
public final class TokenService {

    public static final String SERVICE_PREFIX = "service:";
    private static final String ALGORITHM = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final SecretKeySpec key;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();
    private final SecureRandom random = new SecureRandom();

    public TokenService(byte[] key, Clock clock) {
        if (key.length < 32) {
            throw new IllegalArgumentException("the token key must be at least 32 bytes");
        }
        this.key = new SecretKeySpec(key.clone(), ALGORITHM);
        this.clock = clock;
    }

    /** What a valid token says. {@code subject} is a customer id, or {@code service:<name>}. */
    public record Claims(String subject, Instant issuedAt, Instant expiresAt, String id) {

        public boolean service() {
            return subject.startsWith(SERVICE_PREFIX);
        }
    }

    public String issueCustomer(String customerId, Duration ttl) {
        if (customerId == null || customerId.isBlank() || customerId.startsWith(SERVICE_PREFIX)) {
            throw new IllegalArgumentException("not a customer id");
        }
        return issue(customerId, ttl);
    }

    public String issueService(String serviceName, Duration ttl) {
        return issue(SERVICE_PREFIX + serviceName, ttl);
    }

    private String issue(String subject, Duration ttl) {
        Instant now = clock.instant();
        byte[] id = new byte[12];
        random.nextBytes(id);
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", subject);
        claims.put("iat", now.getEpochSecond());
        claims.put("exp", now.plus(ttl).getEpochSecond());
        claims.put("jti", B64.encodeToString(id));
        try {
            String body = B64.encodeToString(json.writeValueAsBytes(claims));
            return body + "." + B64.encodeToString(mac(body));
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    /** The claims of a token we signed that has not expired; empty for anything else. */
    public Optional<Claims> verify(String token) {
        if (token == null || token.length() > 1024) {
            return Optional.empty();
        }
        int dot = token.indexOf('.');
        if (dot <= 0 || dot != token.lastIndexOf('.')) {
            return Optional.empty();
        }
        String body = token.substring(0, dot);
        try {
            byte[] given = B64D.decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(mac(body), given)) {
                return Optional.empty();
            }
            JsonNode c = json.readTree(B64D.decode(body));
            Instant exp = Instant.ofEpochSecond(c.path("exp").asLong(0));
            String sub = c.path("sub").asText("");
            if (sub.isBlank() || !clock.instant().isBefore(exp)) {
                return Optional.empty();
            }
            return Optional.of(new Claims(sub, Instant.ofEpochSecond(c.path("iat").asLong()), exp, c.path("jti").asText()));
        } catch (IllegalArgumentException | IOException e) {
            return Optional.empty();
        }
    }

    private byte[] mac(String body) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            return mac.doFinal(body.getBytes(StandardCharsets.US_ASCII));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
