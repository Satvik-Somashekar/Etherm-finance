package com.openfinance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A token bucket per caller and path rule: each rule allows N requests a minute, refilled continuously, with a
 * burst of N. The caller is the token's subject when there is a valid one, else the client address, so signing in
 * cannot be brute-forced from one machine and a model-backed chat cannot be run up by one customer. Other Etherm
 * services are not limited: the customer request they carry was limited where it entered.
 * Runs after {@link AuthFilter}. Buckets live in memory, so the limit is per instance.
 */
public class RateLimitFilter extends OncePerRequestFilter {

    private static final int MAX_BUCKETS = 100_000;

    private final Map<String, Integer> rules;
    private final Clock clock;
    private final AntPathMatcher paths = new AntPathMatcher();
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    public RateLimitFilter(SecurityProperties properties, Clock clock) {
        this.rules = properties.rateLimits();
        this.clock = clock;
    }

    private static final class Bucket {
        double tokens;
        long at;

        Bucket(double tokens, long at) {
            this.tokens = tokens;
            this.at = at;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        // The most specific matching pattern wins, so "/**" is only the fallback.
        Map.Entry<String, Integer> rule = rules.entrySet().stream()
                .filter(e -> paths.match(e.getKey(), path))
                .min((a, b) -> paths.getPatternComparator(path).compare(a.getKey(), b.getKey())).orElse(null);
        if (rule == null || rule.getValue() <= 0) {
            chain.doFilter(request, response);
            return;
        }
        Object caller = request.getAttribute(Caller.ATTRIBUTE);
        if (caller instanceof Caller c && c.service()) {
            // Calls between services carry a customer's request that was already limited where it came in.
            chain.doFilter(request, response);
            return;
        }
        String who = caller instanceof Caller c ? "customer:" + c.subject() : "address:" + request.getRemoteAddr();
        long wait = take(rule.getKey() + "|" + who, rule.getValue());
        if (wait > 0) {
            response.setHeader("Retry-After", Long.toString(wait));
            Problems.write(response, 429, "Too many requests; try again in " + wait + " seconds.");
            return;
        }
        chain.doFilter(request, response);
    }

    /** Takes one token; returns 0 when allowed, else the seconds until one is available. */
    long take(String key, int perMinute) {
        long now = clock.millis();
        if (buckets.size() > MAX_BUCKETS) {
            buckets.clear();
        }
        Bucket b = buckets.computeIfAbsent(key, k -> new Bucket(perMinute, now));
        synchronized (b) {
            b.tokens = Math.min(perMinute, b.tokens + (now - b.at) * perMinute / 60_000.0);
            b.at = now;
            if (b.tokens >= 1) {
                b.tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - b.tokens) * 60.0 / perMinute);
        }
    }
}
