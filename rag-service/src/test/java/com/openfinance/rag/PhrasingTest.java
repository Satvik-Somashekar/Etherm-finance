package com.openfinance.rag;

import com.openfinance.core.compute.BreakdownItem;
import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.DateRange;
import com.openfinance.core.compute.Figure;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class PhrasingTest {

    private static final FinancialRecord R = new FinancialRecord("L:1", "u", "L", "F", FiType.DEPOSIT,
            RecordKind.TRANSACTION, Category.FOOD, Direction.DEBIT, LocalDate.of(2026, 8, 1), new BigDecimal("1234.5"),
            "d", "c", Map.of());

    private static ComputedAnswer answer(Intent intent, boolean compare) {
        QueryPlan plan = QueryPlan.of(intent).withCategories(java.util.Set.of(Category.FOOD))
                .withPeriod(new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "last quarter"))
                .withCompare(compare);
        List<Figure> figures = new java.util.ArrayList<>(List.of(
                new Figure("total", intent == Intent.BALANCE ? "Net worth" : "Total", new BigDecimal("1234567.5"), Figure.Unit.INR, List.of("L:1")),
                new Figure("count", "Count", BigDecimal.ONE, Figure.Unit.COUNT, List.of("L:1")),
                new Figure("employeeShare", "Employee", BigDecimal.ONE, Figure.Unit.INR, List.of("L:1")),
                new Figure("employerShare", "Employer", BigDecimal.ONE, Figure.Unit.INR, List.of("L:1"))));
        if (compare) {
            figures.add(new Figure("previousTotal", "Prev", BigDecimal.TEN, Figure.Unit.INR, List.of()));
            figures.add(new Figure("change", "Change", new BigDecimal("-3"), Figure.Unit.INR, List.of()));
            figures.add(new Figure("changePct", "Change", new BigDecimal("-30.0"), Figure.Unit.PERCENT, List.of()));
        }
        return new ComputedAnswer(plan, figures,
                List.of(new BreakdownItem("A", BigDecimal.ONE, List.of("L:1")), new BreakdownItem("B", BigDecimal.ONE, List.of("L:1"))),
                List.of(R), 1);
    }

    @ParameterizedTest
    @EnumSource(Intent.class)
    void templatesHardCodeNoDigits(Intent intent) {
        TemplatePhraser t = new TemplatePhraser();
        String template = t.template(answer(intent, true));
        // Breakdown labels are data inserted by Java; the fixed template text itself has no digits.
        assertThat(template.replaceAll("\\{[a-zA-Z0-9_]+}", "")).doesNotContainPattern("\\d");
    }

    @Test
    void filledTemplateCarriesJavaFormattedFigures() {
        String text = new TemplatePhraser().phrase("q", answer(Intent.SPEND_CATEGORY, true)).text();
        assertThat(text).contains("₹12,34,567.50").contains("down from ₹10.00").contains("(30%)");
        assertThat(text).doesNotContain("{");
    }

    @Test
    void modelDraftWithItsOwnNumberIsRejected() {
        FactSheet facts = FactSheet.of(answer(Intent.SPEND_CATEGORY, false));
        assertThat(ClaudePhraser.isSafe("You spent {total} on food last quarter.", facts, answer(Intent.SPEND_CATEGORY, false))).isTrue();
        assertThat(ClaudePhraser.isSafe("You spent about 12 lakh on food.", facts, answer(Intent.SPEND_CATEGORY, false))).isFalse();
        assertThat(ClaudePhraser.isSafe("You spent {total}, roughly {estimate}.", facts, answer(Intent.SPEND_CATEGORY, false))).isFalse();
        assertThat(ClaudePhraser.isSafe("", facts, answer(Intent.SPEND_CATEGORY, false))).isFalse();
    }

    @Test
    void indianGrouping() {
        assertThat(Inr.format(new BigDecimal("1234567.5"))).isEqualTo("₹12,34,567.50");
        assertThat(Inr.format(new BigDecimal("999"))).isEqualTo("₹999.00");
        assertThat(Inr.format(new BigDecimal("100000"))).isEqualTo("₹1,00,000.00");
        assertThat(Inr.format(new BigDecimal("-25000.456"))).isEqualTo("-₹25,000.46");
    }
}
