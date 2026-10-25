package com.openfinance.ingestion.consent;

import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.core.consent.ConsentViolationException;
import com.openfinance.core.model.FiType;
import com.openfinance.ingestion.RawAccountStore;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentExpiryTest {

    /** A clock the test can move forward. */
    static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-10-25T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
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

    @Test
    void activeConsentReadsAsExpiredAfterItsValidityAndRefusesData() {
        MutableClock clock = new MutableClock();
        ConsentService service = new ConsentService(new RawAccountStore(), clock);
        var c = service.create(new CreateConsentRequest("u@aa", Set.of(FiType.DEPOSIT), Set.of(), null, null,
                LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30), 30));
        service.approve(c.consentId());
        assertThat(service.get(c.consentId()).status()).isEqualTo(ConsentStatus.ACTIVE);

        clock.now = clock.now.plus(Duration.ofDays(31));
        assertThat(service.get(c.consentId()).status()).isEqualTo(ConsentStatus.EXPIRED);
        assertThatThrownBy(() -> service.fetchedAccounts(c.consentId())).isInstanceOf(ConsentViolationException.class);
    }
}
