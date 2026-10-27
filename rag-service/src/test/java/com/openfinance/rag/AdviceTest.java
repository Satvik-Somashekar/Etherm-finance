package com.openfinance.rag;

import com.openfinance.core.compute.Intent;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Suggestions are built from computed figures and keep the conversation's topic across follow-ups. */
class AdviceTest {

    private UpstreamClients upstream;
    private QueryService service;

    @BeforeEach
    void setUp() {
        upstream = mock(UpstreamClients.class);
        List<FinancialRecord> records = new ArrayList<>();
        // Three full months before 25 Oct 2026: Jul, Aug, Sep.
        for (int m = 7; m <= 9; m++) {
            records.add(tx("sal" + m, Category.SALARY, Direction.CREDIT, m, "100000", "ACME"));
            records.add(tx("rent" + m, Category.RENT, Direction.DEBIT, m, "30000", "LANDLORD"));
            records.add(tx("groc" + m, Category.GROCERIES, Direction.DEBIT, m, "10000", "FRESHMART"));
            records.add(tx("food" + m, Category.FOOD, Direction.DEBIT, m, "4000", "ZOMATO"));
        }
        when(upstream.retrieve(any(), any())).thenReturn(new UpstreamClients.Retrieved(List.of("CN-1"), records.size(), records));
        service = new QueryService(upstream, new TemplatePhraser(), Clock.systemUTC(),
                new PhrasingConfig.AsOf(LocalDate.of(2026, 10, 25)));
    }

    private static FinancialRecord tx(String id, Category c, Direction d, int month, String amount, String who) {
        return new FinancialRecord(id, "C1", "A1", "FIP", FiType.DEPOSIT, RecordKind.TRANSACTION, c, d,
                LocalDate.of(2026, month, 5), new BigDecimal(amount), c.name(), who, null);
    }

    private QueryResponse ask(String question, List<String> history) {
        return service.answer(new QueryRequest(question, null, List.of("CN-1"), null, history));
    }

    @Test
    void aSuggestionFocusesOnTheLargestFlexibleSpendAndCarriesComputedFigures() {
        QueryResponse r = ask("How can I reduce my spending?", null);
        assertThat(r.intent()).isEqualTo(Intent.ADVICE);
        assertThat(r.answer()).startsWith("Suggestion: Groceries").contains("Freshmart")
                .endsWith(QueryService.ADVICE_NOTE).doesNotContain("{");
        // 10,000 a month on groceries; a 10% trim frees 1,000 a month and 12,000 a year.
        assertThat(r.figures()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("focusMonthly");
            assertThat(f.value()).isEqualByComparingTo("10000");
        });
        assertThat(r.figures()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("trim10Yearly");
            assertThat(f.value()).isEqualByComparingTo("12000");
        });
        // Saved 56,000 of 100,000 a month now (56.0%), 57,000 after the trim (57.0%).
        assertThat(r.figures()).anySatisfy(f -> {
            assertThat(f.key()).isEqualTo("newSavingsRate");
            assertThat(f.value()).isEqualByComparingTo("57.0");
        });
        assertThat(r.citations()).isNotEmpty();
    }

    @Test
    void aFollowUpTakesItsTopicFromTheEarlierQuestion() {
        QueryResponse r = ask("How can I reduce it?", List.of("How much did I spend on food last quarter?"));
        assertThat(r.answer()).startsWith("Suggestion: Food");
        assertThat(r.period().label()).isEqualTo("last quarter");
    }

    @Test
    void aFixedCostIsSaidToBeHardToCutAndPointsToAFlexibleOne() {
        QueryResponse r = ask("How can I reduce my rent?", null);
        assertThat(r.answer()).startsWith("Suggestion: Rent is a fixed cost").contains("Groceries");
    }

    @Test
    void investmentAdviceIsStillDeclined() {
        QueryResponse r = ask("Should I invest more in stocks?", null);
        assertThat(r.intent()).isEqualTo(Intent.UNSUPPORTED);
    }

    @Test
    void aSuggestionNeedsTheDepositSection() {
        QueryResponse r = service.answer(new QueryRequest("How can I reduce my spending?", null, List.of("CN-1"),
                Set.of(FiType.MUTUAL_FUNDS), null));
        assertThat(r.answer()).startsWith("That is outside the section");
        verify(upstream, never()).retrieve(any(), any());
    }
}
