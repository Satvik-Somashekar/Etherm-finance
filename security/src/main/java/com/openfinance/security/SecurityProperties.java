package com.openfinance.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * {@code etherm.security.*}.
 *
 * @param authKey       base64 key, 32 bytes or more, shared by every service ({@code ETHERM_AUTH_KEY}); empty
 *                      uses a fixed development key and says so at start-up
 * @param requireKey    refuse to start without {@code authKey} ({@code ETHERM_REQUIRE_AUTH_KEY=true}); set it
 *                      anywhere real data could be
 * @param publicPaths   paths that need no token (Ant patterns)
 * @param servicePaths  paths only another Etherm service may call
 * @param maxBodyBytes  larger request bodies are refused with 413
 * @param rateLimits    requests per minute per caller (customer, service or, before sign-in, client address),
 *                      by path pattern; the most specific matching pattern applies, {@code /**} is the fallback
 * @param serviceTokenTtl lifetime of the token a service attaches to its own calls
 */
@ConfigurationProperties("etherm.security")
public record SecurityProperties(
        @DefaultValue("") String authKey,
        @DefaultValue("false") boolean requireKey,
        @DefaultValue({"/actuator/health", "/actuator/health/**", "/error"}) List<String> publicPaths,
        @DefaultValue({}) List<String> servicePaths,
        @DefaultValue("65536") long maxBodyBytes,
        Map<String, Integer> rateLimits,
        @DefaultValue("60s") Duration serviceTokenTtl) {

    public Map<String, Integer> rateLimits() {
        return rateLimits == null || rateLimits.isEmpty() ? Map.of("/**", 300) : rateLimits;
    }
}
