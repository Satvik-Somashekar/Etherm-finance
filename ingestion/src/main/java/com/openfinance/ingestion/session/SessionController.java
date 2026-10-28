package com.openfinance.ingestion.session;

import com.openfinance.ingestion.RawAccountStore;
import com.openfinance.security.TokenService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Sign-in. A customer known to the simulated providers proves who they are with a passcode and gets a signed,
 * short-lived token that every service accepts for that customer only.
 *
 * <p>This is still a demo: every synthetic customer shares one passcode ({@code ETHERM_DEMO_PASSCODE}), because
 * there is no real identity provider behind it. What is real is everything after: the passcode is held only as a
 * PBKDF2 hash and compared in constant time, an unknown customer costs the same time as a wrong passcode and gets
 * the same answer, five failures lock that customer out for fifteen minutes, sign-in is rate-limited per client
 * address, and the token binds every later request to the one customer.
 */
@RestController
@RequestMapping("/api/session")
public class SessionController {

    private static final Logger log = LoggerFactory.getLogger(SessionController.class);
    private static final int ITERATIONS = 210_000;
    private static final int MAX_FAILURES = 5;
    private static final Duration LOCKOUT = Duration.ofMinutes(15);
    private static final String REFUSED = "That customer id and passcode do not match.";

    private final RawAccountStore store;
    private final TokenService tokens;
    private final Clock clock;
    private final Duration ttl;
    private final byte[] salt = new byte[16];
    private final byte[] hash;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public SessionController(RawAccountStore store, TokenService tokens, Clock clock,
                             @Value("${etherm.session.demo-passcode:etherm-demo}") String passcode,
                             @Value("${etherm.session.ttl:60m}") Duration ttl) {
        this.store = store;
        this.tokens = tokens;
        this.clock = clock;
        this.ttl = ttl;
        new SecureRandom().nextBytes(salt);
        this.hash = pbkdf2(passcode.toCharArray());
        if ("etherm-demo".equals(passcode)) {
            log.warn("Sign-in uses the public demo passcode; set ETHERM_DEMO_PASSCODE to change it");
        }
    }

    public record SignIn(String customerId, String passcode) {
    }

    public record Session(String customerId, String token, Instant expiresAt) {
    }

    private record Failures(int count, Instant lockedUntil) {
    }

    @PostMapping
    public Session signIn(@RequestBody SignIn request) {
        String customerId = request.customerId() == null ? "" : request.customerId().trim();
        Instant now = clock.instant();
        Failures f = failures.get(customerId);
        if (f != null && f.lockedUntil() != null && now.isBefore(f.lockedUntil())) {
            throw new SignInRefused("Too many failed attempts for this customer; try again later.");
        }
        boolean passcodeOk = MessageDigest.isEqual(hash,
                pbkdf2(request.passcode() == null ? new char[0] : request.passcode().toCharArray()));
        if (!passcodeOk || !store.customers().contains(customerId)) {
            if (failures.size() > 10_000) {
                // Bounded: a flood of made-up ids cannot grow this without limit (the address rate limit applies too).
                failures.clear();
            }
            failures.merge(customerId, new Failures(1, null), (old, one) -> {
                int count = old.count() + 1;
                return new Failures(count, count >= MAX_FAILURES ? now.plus(LOCKOUT) : null);
            });
            throw new SignInRefused(REFUSED);
        }
        failures.remove(customerId);
        return new Session(customerId, tokens.issueCustomer(customerId, ttl), now.plus(ttl));
    }

    private byte[] pbkdf2(char[] passcode) {
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(new PBEKeySpec(passcode, salt, ITERATIONS, 256)).getEncoded();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    static class SignInRefused extends RuntimeException {
        SignInRefused(String message) {
            super(message);
        }
    }

    @ExceptionHandler(SignInRefused.class)
    ProblemDetail refused(SignInRefused e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, e.getMessage());
    }
}
