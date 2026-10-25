package com.openfinance.retrieval;

import com.openfinance.core.compute.RecordFilter;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.core.consent.ConsentViolationException;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RetrievalServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-25T00:00:00Z");
    private static final String CUSTOMER = "user001@etherm-aa";

    private final UpstreamClients upstream = mock(UpstreamClients.class);
    private final RetrievalService service = new RetrievalService(upstream, Clock.fixed(NOW, ZoneOffset.UTC));

    private static FinancialRecord txn(String id, FiType type, Category cat, LocalDate date, String amount) {
        return new FinancialRecord(id, CUSTOMER, "LNK", "FIP", type, RecordKind.TRANSACTION, cat, Direction.DEBIT, date,
                new BigDecimal(amount), "d", "c", Map.of());
    }

    private static ConsentArtifact consent(String id, ConsentStatus status, Set<FiType> types) {
        return new ConsentArtifact(id, CUSTOMER, status, "101", "Wealth", types, Instant.parse("2026-01-01T00:00:00Z"),
                Instant.parse("2027-01-01T00:00:00Z"), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 9, 30),
                ConsentArtifact.FetchType.PERIODIC);
    }

    private final List<FinancialRecord> records = List.of(
            txn("A:1", FiType.DEPOSIT, Category.FOOD, LocalDate.of(2026, 8, 1), "100"),
            txn("A:2", FiType.DEPOSIT, Category.RENT, LocalDate.of(2026, 8, 5), "900"),
            txn("A:3", FiType.DEPOSIT, Category.FOOD, LocalDate.of(2025, 12, 1), "50"),
            txn("B:1", FiType.EQUITIES, Category.EQUITY_BUY, LocalDate.of(2026, 8, 2), "5000"));

    @Test
    void appliesConsentScopeBeforeTheQueryFilter() {
        when(upstream.consent("CN-1")).thenReturn(consent("CN-1", ConsentStatus.ACTIVE, Set.of(FiType.DEPOSIT)));
        when(upstream.records("CN-1")).thenReturn(records);

        RetrievalResult result = service.retrieve(new RetrievalRequest(List.of("CN-1"),
                new RecordFilter(null, null, Set.of(Category.FOOD), null, null, null)));

        // A:3 is outside the consented data range and B:1 is an unconsented FI type.
        assertThat(result.visible()).isEqualTo(2);
        assertThat(result.records()).extracting(FinancialRecord::recordId).containsExactly("A:1");
    }

    @Test
    void revokedConsentStopsRetrievalBeforeRecordsAreRead() {
        when(upstream.consent("CN-1")).thenReturn(consent("CN-1", ConsentStatus.REVOKED, Set.of(FiType.DEPOSIT)));
        assertThatThrownBy(() -> service.retrieve(new RetrievalRequest(List.of("CN-1"), null)))
                .isInstanceOf(ConsentViolationException.class);
        verify(upstream, never()).records("CN-1");
    }

    @Test
    void overlappingConsentsDoNotDoubleCount() {
        when(upstream.consent("CN-1")).thenReturn(consent("CN-1", ConsentStatus.ACTIVE, Set.of(FiType.DEPOSIT)));
        when(upstream.consent("CN-2")).thenReturn(consent("CN-2", ConsentStatus.ACTIVE, Set.of(FiType.DEPOSIT, FiType.EQUITIES)));
        when(upstream.records("CN-1")).thenReturn(records);
        when(upstream.records("CN-2")).thenReturn(records);

        RetrievalResult result = service.retrieve(new RetrievalRequest(List.of("CN-1", "CN-2"), null));
        assertThat(result.records()).extracting(FinancialRecord::recordId).containsExactly("A:1", "B:1", "A:2");
    }

    @Test
    void requestWithoutConsentIsRejected() {
        assertThatThrownBy(() -> service.retrieve(new RetrievalRequest(List.of(), null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
