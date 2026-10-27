package com.openfinance.rag;

import com.openfinance.core.compute.RecordFilter;
import com.openfinance.core.model.FiType;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A Knowledge Base section only ever removes data from what the consents allow. */
class ScopeTest {

    private UpstreamClients upstream;
    private QueryService service;

    @BeforeEach
    void setUp() {
        upstream = mock(UpstreamClients.class);
        when(upstream.retrieve(any(), any())).thenReturn(new UpstreamClients.Retrieved(List.of("CN-1"), 0, List.of()));
        service = new QueryService(upstream, new TemplatePhraser(), Clock.systemUTC(),
                new PhrasingConfig.AsOf(LocalDate.of(2026, 10, 25)));
    }

    private QueryResponse ask(String question, Set<FiType> scope) {
        return service.answer(new QueryRequest(question, null, List.of("CN-1"), scope));
    }

    private RecordFilter retrievedWith() {
        ArgumentCaptor<RecordFilter> captor = ArgumentCaptor.forClass(RecordFilter.class);
        verify(upstream).retrieve(any(), captor.capture());
        return captor.getValue();
    }

    @Test
    void aQuestionOutsideTheSectionIsDeclinedWithoutReadingAnyData() {
        QueryResponse r = ask("How much did I spend on groceries last month?", Set.of(FiType.INSURANCE_POLICIES));
        assertThat(r.answer()).startsWith("That is outside the section you selected (Insurance)");
        assertThat(r.figures()).isEmpty();
        assertThat(r.citations()).isEmpty();
        verify(upstream, never()).retrieve(any(), any());
    }

    @Test
    void netWorthInsideASectionIsOnlyThatSectionsValue() {
        ask("What is my net worth?", Set.of(FiType.MUTUAL_FUNDS));
        assertThat(retrievedWith().fiTypes()).containsExactly(FiType.MUTUAL_FUNDS);
    }

    @Test
    void exportsAreLimitedToTheSection() {
        ask("Export my transactions", Set.of(FiType.DEPOSIT));
        assertThat(retrievedWith().fiTypes()).containsExactly(FiType.DEPOSIT);
    }

    @Test
    void aMatchingSectionAnswersNormally() {
        ask("How much did I pay in insurance premiums last quarter?", Set.of(FiType.INSURANCE_POLICIES));
        assertThat(retrievedWith().fiTypes()).containsExactly(FiType.INSURANCE_POLICIES);
    }

    @Test
    void noScopeMeansNoNarrowing() {
        ask("What is my net worth?", null);
        assertThat(retrievedWith().fiTypes()).hasSize(4);
    }

    @Test
    void theReportNeedsTheDepositSection() {
        assertThat(ask("Generate a monthly report", Set.of(FiType.EPF)).answer()).startsWith("That is outside");
    }
}
