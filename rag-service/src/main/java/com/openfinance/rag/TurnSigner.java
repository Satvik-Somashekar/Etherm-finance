package com.openfinance.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Signs each answer the service gives, so that an earlier "assistant" turn sent back with a follow-up can be told
 * apart from text the client made up. Only signed turns reach the model as its own words, and only their numbers
 * may be repeated without a lookup; anything else could smuggle a number past the digit check or put words in the
 * model's mouth. The signature binds the answer to the customer (or consents) it was given to.
 *
 * <p>The key comes from {@code ETHERM_TURN_KEY} (base64, at least 32 bytes) so signatures survive a restart and
 * several instances agree; without it a random key is made at start-up and earlier conversations simply lose their
 * signed history.
 */
@Component
public class TurnSigner {

    private static final Logger log = LoggerFactory.getLogger(TurnSigner.class);
    private static final String ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public TurnSigner(@Value("${ETHERM_TURN_KEY:}") String configured) {
        byte[] bytes;
        if (configured == null || configured.isBlank()) {
            bytes = new byte[32];
            new SecureRandom().nextBytes(bytes);
            log.info("ETHERM_TURN_KEY is not set; conversation turns are signed with a key made at start-up");
        } else {
            bytes = Base64.getDecoder().decode(configured.trim());
            if (bytes.length < 32) {
                throw new IllegalStateException("ETHERM_TURN_KEY must be at least 32 bytes, base64-encoded");
            }
        }
        this.key = new SecretKeySpec(bytes, ALGORITHM);
    }

    /** Who an answer was given to: the customer, or the consents when the request named consents instead. */
    static String principal(String customerId, java.util.List<String> consentIds) {
        if (customerId != null && !customerId.isBlank()) {
            return "customer:" + customerId;
        }
        return "consents:" + (consentIds == null ? "" : String.join(",", consentIds.stream().sorted().toList()));
    }

    public String sign(String principal, String text) {
        try {
            Mac mac = Mac.getInstance(ALGORITHM);
            mac.init(key);
            mac.update(principal.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            mac.update(text.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Constant-time check that {@code signature} is ours for this principal and text. */
    public boolean verify(String principal, String text, String signature) {
        if (signature == null || text == null) {
            return false;
        }
        return MessageDigest.isEqual(sign(principal, text).getBytes(StandardCharsets.US_ASCII),
                signature.getBytes(StandardCharsets.US_ASCII));
    }
}
