package com.openfinance.rag;

import com.openfinance.core.compute.NetWorthHistory;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OverviewServiceTest {

    private static final List<String> CONSENTS = List.of("CN-user001-BANK");

    private final UpstreamClients upstream = mock(UpstreamClients.class);
    private final QueryService queries = mock(QueryService.class);
    private final OverviewService service = new OverviewService(upstream, queries);

    @Test
    void buildsHistoryActivityAndInsightsUnderTheCustomersConsents() {
        List<FinancialRecord> records = new ArrayList<>();
        records.add(record("A:SUMMARY", RecordKind.BALANCE, Category.ACCOUNT_BALANCE, null, LocalDate.of(2026, 9, 30), "1000"));
        for (int day = 1; day <= 8; day++) {
            records.add(record("A:T" + day, RecordKind.TRANSACTION, Category.FOOD, Direction.DEBIT,
                    LocalDate.of(2026, 8 + day % 2, day), "10"));
        }
        when(queries.consents(any())).thenReturn(CONSENTS);
        when(upstream.retrieve(eq(CONSENTS), any()))
                .thenReturn(new UpstreamClients.Retrieved(CONSENTS, records.size(), records));
        when(queries.answer(any())).thenAnswer(inv -> answer(((QueryRequest) inv.getArgument(0)).question()));

        OverviewResponse overview = service.overview("user001@etherm-aa");

        assertThat(overview.consentIds()).isEqualTo(CONSENTS);
        assertThat(overview.history()).extracting(OverviewResponse.HistoryPoint::value)
                .containsExactly(new BigDecimal("1040.00"), new BigDecimal("1000.00"));
        assertThat(overview.activity()).hasSize(OverviewService.ACTIVITY_SIZE)
                .extracting(OverviewResponse.Activity::date).isSortedAccordingTo((a, b) -> b.compareTo(a));
        // The insight with nothing to cite is dropped.
        assertThat(overview.insights()).hasSize(OverviewService.INSIGHT_QUESTIONS.size() - 1)
                .allSatisfy(i -> assertThat(i.grounding().consentIds()).isEqualTo(CONSENTS));
        assertThat(NetWorthHistory.filter().fiTypes()).doesNotContain(FiType.INSURANCE_POLICIES);
    }

    private static FinancialRecord record(String id, RecordKind kind, Category category, Direction direction,
                                          LocalDate date, String amount) {
        return new FinancialRecord(id, "user001@etherm-aa", "A", "ETHERM-BANK-FIP", FiType.DEPOSIT, kind, category,
                direction, date, new BigDecimal(amount), "test", null, null);
    }

    private static QueryResponse answer(String question) {
        boolean empty = question.equals(OverviewService.INSIGHT_QUESTIONS.getLast());
        List<QueryResponse.Citation> citations = empty ? List.of()
                : List.of(new QueryResponse.Citation("A:T1", "A", "ETHERM-BANK-FIP", FiType.DEPOSIT, LocalDate.now(),
                        BigDecimal.TEN, "test", null));
        return new QueryResponse(question, "answer", "template", null, null, List.of(), List.of(), citations,
                new QueryResponse.Grounding(CONSENTS, 9, 9, citations.size(), LocalDate.of(2026, 10, 25)));
    }
}
