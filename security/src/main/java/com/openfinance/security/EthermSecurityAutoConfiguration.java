package com.openfinance.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.Base64;

/**
 * Turns on, in every service that depends on this module: security headers, the body limit, token checks and
 * rate limits (in that order), and a service token on every call the service makes with a Spring
 * {@code RestClient}.
 */
@AutoConfiguration
@EnableConfigurationProperties(SecurityProperties.class)
@Import(AccessDeniedAdvice.class)
public class EthermSecurityAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(EthermSecurityAutoConfiguration.class);
    /** Used only when no key is configured, so the services work together out of the box on one machine. */
    private static final String DEVELOPMENT_SEED = "etherm-development-key-not-for-real-data";

    @Bean
    @ConditionalOnMissingBean
    Clock securityClock() {
        return Clock.systemUTC();
    }

    @Bean
    TokenService tokenService(SecurityProperties properties, Clock clock) {
        String configured = properties.authKey();
        if (configured == null || configured.isBlank()) {
            if (properties.requireKey()) {
                throw new IllegalStateException("ETHERM_AUTH_KEY is required (ETHERM_REQUIRE_AUTH_KEY=true)");
            }
            log.warn("ETHERM_AUTH_KEY is not set: tokens are signed with the public development key. "
                    + "Set ETHERM_AUTH_KEY (base64, 32+ bytes, the same in every service) before using real data.");
            return new TokenService(sha256(DEVELOPMENT_SEED), clock);
        }
        byte[] key = Base64.getDecoder().decode(configured.trim());
        return new TokenService(key, clock);
    }

    @Bean
    FilterRegistrationBean<SecurityHeadersFilter> ethermSecurityHeaders() {
        return register(new SecurityHeadersFilter(), Ordered.HIGHEST_PRECEDENCE + 10);
    }

    @Bean
    FilterRegistrationBean<BodyLimitFilter> ethermBodyLimit(SecurityProperties properties) {
        return register(new BodyLimitFilter(properties.maxBodyBytes()), Ordered.HIGHEST_PRECEDENCE + 20);
    }

    @Bean
    FilterRegistrationBean<AuthFilter> ethermAuth(TokenService tokens, SecurityProperties properties) {
        return register(new AuthFilter(tokens, properties), Ordered.HIGHEST_PRECEDENCE + 30);
    }

    @Bean
    FilterRegistrationBean<RateLimitFilter> ethermRateLimit(SecurityProperties properties, Clock clock) {
        return register(new RateLimitFilter(properties, clock), Ordered.HIGHEST_PRECEDENCE + 40);
    }

    /** Every outgoing call to another Etherm service carries a short-lived token naming this service. */
    @Bean
    RestClientCustomizer ethermServiceToken(TokenService tokens, SecurityProperties properties,
                                            @Value("${spring.application.name:etherm}") String name) {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            request.getHeaders().set(HttpHeaders.AUTHORIZATION,
                    "Bearer " + tokens.issueService(name, properties.serviceTokenTtl()));
            return execution.execute(request, body);
        });
    }

    private static <F extends jakarta.servlet.Filter> FilterRegistrationBean<F> register(F filter, int order) {
        FilterRegistrationBean<F> bean = new FilterRegistrationBean<>(filter);
        bean.setOrder(order);
        bean.addUrlPatterns("/*");
        return bean;
    }

    private static byte[] sha256(String s) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
