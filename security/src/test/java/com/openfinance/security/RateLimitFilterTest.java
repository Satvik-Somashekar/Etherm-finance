package com.openfinance.security;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimitFilterTest {

    /** A clock the test moves by hand. */
    static final class Manual extends Clock {
        Instant now = Instant.parse("2026-10-28T10:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private static SecurityProperties props() {
        return new SecurityProperties("", false, List.of(), List.of(), 1024, Map.of("/**", 10), Duration.ofSeconds(60));
    }

    @Test
    void aBurstUpToTheLimitPassesThenTheCallerWaits() {
        Manual clock = new Manual();
        RateLimitFilter limiter = new RateLimitFilter(props(), clock);
        for (int i = 0; i < 10; i++) {
            assertThat(limiter.take("k", 10)).isZero();
        }
        assertThat(limiter.take("k", 10)).isEqualTo(6);
        clock.now = clock.now.plusSeconds(6);
        assertThat(limiter.take("k", 10)).isZero();
    }

    @Test
    void callersHaveSeparateBuckets() {
        RateLimitFilter limiter = new RateLimitFilter(props(), new Manual());
        for (int i = 0; i < 10; i++) {
            limiter.take("a", 10);
        }
        assertThat(limiter.take("a", 10)).isPositive();
        assertThat(limiter.take("b", 10)).isZero();
    }
}
