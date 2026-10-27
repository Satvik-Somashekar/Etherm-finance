package com.openfinance.rag;

import com.openfinance.core.compute.Intent;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import com.openfinance.core.compute.RecordFilter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Each Knowledge Base section gets suggestions from its own records only. */
class SectionAdviceTest {

    private UpstreamClients upstream;
    private QueryService service;

    @BeforeEach
    void setUp() {
        upstream = mock(UpstreamClients.class);
        List<FinancialRecord> all = new ArrayList<>();
        for (int m = 7; m <= 9; m++) {
            all.add(tx("sal" + m, FiType.DEPOSIT, Category.SALARY, Direction.CREDIT, m, "100000", "ACME", Map.of()));
            all.add(tx("groc" + m, FiType.DEPOSIT, Category.GROCERIES, Direction.DEBIT, m, "20000", "FRESHMART", Map.of()));
            all.add(tx("sip" + m, FiType.MUTUAL_FUNDS, Category.MF_PURCHASE, Direction.DEBIT, m, "5000", "AMC", Map.of()));
            all.add(tx("epf" + m, FiType.EPF, Category.EPF_CONTRIBUTION, Direction.CREDIT, m, "6000", "EMP", Map.of()));
        }
        all.add(snap("cash", FiType.DEPOSIT, RecordKind.BALANCE, Category.ACCOUNT_BALANCE, "120000", Map.of()));
        all.add(snap("mfsum", FiType.MUTUAL_FUNDS, RecordKind.BALANCE, Category.ACCOUNT_BALANCE, "150000", Map.of("costValue", "100000")));
        all.add(snap("h1", FiType.MUTUAL_FUNDS, RecordKind.HOLDING, Category.HOLDING_VALUE, "90000", Map.of("schemeCategory", "EQUITY")));
        all.add(snap("h2", FiType.MUTUAL_FUNDS, RecordKind.HOLDING, Category.HOLDING_VALUE, "60000", Map.of("schemeCategory", "DEBT")));
        all.add(snap("s1", FiType.EQUITIES, RecordKind.HOLDING, Category.HOLDING_VALUE, "70000", Map.of()));
        all.add(snap("s2", FiType.EQUITIES, RecordKind.HOLDING, Category.HOLDING_VALUE, "30000", Map.of()));
        all.add(snap("epfsum", FiType.EPF, RecordKind.BALANCE, Category.ACCOUNT_BALANCE, "500000", Map.of("employerBalance", "250000")));
        all.add(snap("term", FiType.INSURANCE_POLICIES, RecordKind.POLICY, Category.POLICY_COVER, "5000000",
                Map.of("policyType", "TERM", "policyName", "Term Plan", "premiumAmount", "12000", "premiumFrequency", "ANNUAL")));
        // The fake retrieval returns every record; the engine's own filter picks what the question needs.
        when(upstream.retrieve(any(), any())).thenReturn(new UpstreamClients.Retrieved(List.of("CN-1"), all.size(), all));
        service = new QueryService(upstream, new TemplatePhraser(), Clock.systemUTC(),
                new PhrasingConfig.AsOf(LocalDate.of(2026, 10, 25)));
    }

    private static FinancialRecord tx(String id, FiType t, Category c, Direction d, int month, String amount, String who,
                                      Map<String, String> attrs) {
        return new FinancialRecord(id, "C1", "A1", "FIP", t, RecordKind.TRANSACTION, c, d,
                LocalDate.of(2026, month, 5), new BigDecimal(amount), c.name(), who, attrs);
    }

    private static FinancialRecord snap(String id, FiType t, RecordKind k, Category c, String amount, Map<String, String> attrs) {
        return new FinancialRecord(id, "C1", "A1", "FIP", t, k, c, null, LocalDate.of(2026, 9, 30),
                new BigDecimal(amount), id, id, attrs);
    }

    private QueryResponse ask(String question, FiType scope) {
        return service.answer(new QueryRequest(question, null, List.of("CN-1"), scope == null ? null : Set.of(scope), null));
    }

    private static BigDecimal figure(QueryResponse r, String key) {
        return r.figures().stream().filter(f -> f.key().equals(key)).findFirst().orElseThrow().value();
    }

    @Test
    void insuranceInItsOwnSectionCannotSeeIncomeAndSaysSo() {
        QueryResponse r = ask("Is my insurance cover enough?", FiType.INSURANCE_POLICIES);
        assertThat(r.answer()).startsWith("Suggestion: Your policies carry ₹50,00,000.00 of cover")
                .contains("no health cover").contains("cannot see your income");
        ArgumentCaptor<RecordFilter> captor = ArgumentCaptor.forClass(RecordFilter.class);
        verify(upstream).retrieve(any(), captor.capture());
        assertThat(captor.getValue().fiTypes()).containsExactly(FiType.INSURANCE_POLICIES);
    }

    @Test
    void insuranceInAllAccountsComparesCoverWithSalary() {
        QueryResponse r = ask("Is my insurance cover enough?", null);
        // Salary 100,000 a month = 12,00,000 a year; term cover 50,00,000 is about 4.2 times; 10 times is 1,20,00,000.
        assertThat(figure(r, "termMultiple")).isEqualByComparingTo("4.2");
        assertThat(figure(r, "termShortfall")).isEqualByComparingTo("7000000");
        assertThat(r.answer()).contains("top-up of roughly ₹70,00,000.00");
    }

    @Test
    void fundsAreJudgedOnSpreadAndGain() {
        QueryResponse r = ask("How diversified are my mutual funds?", FiType.MUTUAL_FUNDS);
        // 90,000 of 150,000 is 60.0%, above the 40% flag; value 150,000 against cost 100,000 is up 50%.
        assertThat(figure(r, "topShare")).isEqualByComparingTo("60.0");
        assertThat(figure(r, "gainPct")).isEqualByComparingTo("50.0");
        assertThat(r.answer()).contains("up ₹50,000.00").contains("above the 40%").contains("Equity funds make up 60%");
    }

    @Test
    void stocksAreJudgedOnConcentration() {
        QueryResponse r = ask("Is my stock portfolio too concentrated?", FiType.EQUITIES);
        assertThat(figure(r, "topShare")).isEqualByComparingTo("70.0");
        assertThat(r.answer()).contains("above the 25%").contains("not a view on any company");
    }

    @Test
    void epfProjectsOneYearOfContributionsBeforeInterest() {
        QueryResponse r = ask("How is my EPF growing?", FiType.EPF);
        assertThat(figure(r, "yearlyContribution")).isEqualByComparingTo("72000");
        assertThat(figure(r, "balanceInYear")).isEqualByComparingTo("572000");
        assertThat(figure(r, "employerShare")).isEqualByComparingTo("50.0");
    }

    @Test
    void overallMixAndEmergencyCushion() {
        QueryResponse r = ask("How am I doing overall?", null);
        // Cash 120,000 against 20,000 a month of spending is 6.0 months, which meets the 6 month aim.
        assertThat(figure(r, "runwayMonths")).isEqualByComparingTo("6.0");
        assertThat(r.answer()).startsWith("Suggestion: Your net worth is").contains("you have that cushion covered");
    }

    @Test
    void aGenericQuestionIsAboutTheSectionTheCustomerIsIn() {
        assertThat(ask("What should I do to improve?", FiType.EPF).answer()).startsWith("Suggestion: Your EPF balance");
        assertThat(ask("What should I do to improve?", FiType.MUTUAL_FUNDS).answer()).startsWith("Suggestion: Your mutual funds");
    }

    @Test
    void aQuestionAboutAnotherAreaIsDeclinedWithoutReadingData() {
        QueryResponse r = ask("Is my stock portfolio too concentrated?", FiType.INSURANCE_POLICIES);
        assertThat(r.answer()).startsWith("That is outside the section");
        verify(upstream, never()).retrieve(any(), any());
    }

    @Test
    void anUnanswerableQuestionGetsTheSectionsOwnHelp() {
        QueryResponse r = ask("What is the weather today?", FiType.INSURANCE_POLICIES);
        assertThat(r.intent()).isEqualTo(Intent.UNSUPPORTED);
        assertThat(r.answer()).startsWith("In Insurance Policies I can");
        verify(upstream, never()).retrieve(any(), any());
    }

    @Test
    void productPicksAndPredictionsAreStillDeclined() {
        assertThat(ask("Which mutual fund should I buy?", null).intent()).isEqualTo(Intent.UNSUPPORTED);
        assertThat(ask("Will my stocks go up?", null).intent()).isEqualTo(Intent.UNSUPPORTED);
    }
}
