package com.openfinance.core.consent;

import com.openfinance.core.TestData;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ConsentScopeTest {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private final List<FinancialRecord> records = TestData.records(0);

    private ConsentArtifact consent(ConsentStatus status, Set<FiType> types, LocalDate from, LocalDate to, Instant expiry) {
        return new ConsentArtifact("CN-1", "user001@etherm-aa", status, "101", "Wealth management", types,
                Instant.parse("2026-01-01T00:00:00Z"), expiry, from, to, ConsentArtifact.FetchType.PERIODIC);
    }

    private ConsentArtifact active(Set<FiType> types, LocalDate from, LocalDate to) {
        return consent(ConsentStatus.ACTIVE, types, from, to, Instant.parse("2027-01-01T00:00:00Z"));
    }

    @Test
    void keepsOnlyConsentedFiTypes() {
        List<FinancialRecord> visible = ConsentScope.apply(active(Set.of(FiType.DEPOSIT, FiType.EPF), TestData.FROM, TestData.TO), records, NOW);
        assertThat(visible).isNotEmpty().allMatch(r -> r.fiType() == FiType.DEPOSIT || r.fiType() == FiType.EPF);
        assertThat(records).anyMatch(r -> r.fiType() == FiType.EQUITIES);
    }

    @Test
    void dropsTransactionsOutsideTheDataRangeButKeepsBalances() {
        LocalDate from = LocalDate.of(2026, 4, 1);
        LocalDate to = LocalDate.of(2026, 6, 30);
        List<FinancialRecord> visible = ConsentScope.apply(active(Set.of(FiType.DEPOSIT), from, to), records, NOW);
        assertThat(visible).filteredOn(r -> r.kind() == RecordKind.TRANSACTION)
                .isNotEmpty()
                .allMatch(r -> !r.date().isBefore(from) && !r.date().isAfter(to));
        assertThat(visible).anyMatch(r -> r.kind() == RecordKind.BALANCE);
    }

    @Test
    void anotherCustomersRecordsAreNeverVisible() {
        List<FinancialRecord> visible = ConsentScope.apply(active(Set.of(FiType.values()), TestData.FROM, TestData.TO),
                TestData.records(1), NOW);
        assertThat(visible).isEmpty();
    }

    @Test
    void revokedPausedAndPendingConsentsExposeNothing() {
        for (ConsentStatus s : List.of(ConsentStatus.REVOKED, ConsentStatus.PAUSED, ConsentStatus.PENDING, ConsentStatus.EXPIRED)) {
            ConsentArtifact c = consent(s, Set.of(FiType.DEPOSIT), TestData.FROM, TestData.TO, Instant.parse("2027-01-01T00:00:00Z"));
            assertThatThrownBy(() -> ConsentScope.apply(c, records, NOW))
                    .isInstanceOf(ConsentViolationException.class).hasMessageContaining(s.name());
        }
    }

    @Test
    void activeButPastExpiryIsRejected() {
        ConsentArtifact c = consent(ConsentStatus.ACTIVE, Set.of(FiType.DEPOSIT), TestData.FROM, TestData.TO,
                Instant.parse("2026-10-01T00:00:00Z"));
        assertThatThrownBy(() -> ConsentScope.apply(c, records, NOW)).hasMessageContaining("expired");
    }
}
