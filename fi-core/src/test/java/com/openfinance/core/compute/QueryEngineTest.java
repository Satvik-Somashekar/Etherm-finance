package com.openfinance.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.TestData;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.normalize.SourceAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks the compute layer against totals taken straight from the raw FIP payloads, without going
 * through the normalisers, so a normalisation or compute bug cannot hide behind itself.
 */
class QueryEngineTest {

    private static final DateRange Q3 = new DateRange(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 9, 30), "last quarter");
    private static final DateRange Q2 = new DateRange(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30), "Q2");

    private final QueryEngine engine = new QueryEngine();
    private final List<FinancialRecord> records = TestData.records(0);
    private final List<SourceAccount> raw = TestData.sources(0);

    private ComputedAnswer run(QueryPlan plan) {
        RecordFilter f = engine.retrievalFilter(plan);
        return engine.compute(plan, records.stream().filter(f::matches).toList());
    }

    @Test
    void groceriesSpendMatchesRawNarrations() {
        ComputedAnswer a = run(QueryPlan.of(Intent.SPEND_CATEGORY).withCategories(Set.of(Category.GROCERIES)).withPeriod(Q3));
        BigDecimal expected = rawSum("deposit", "transactionTimestamp", Q3,
                t -> t.get("type").asText().equals("DEBIT") && t.get("narration").asText().contains("groceries"));
        assertThat(a.figure("total").value()).isEqualByComparingTo(expected);
        assertThat(a.figure("total").recordIds()).hasSize(a.citations().size());
    }

    @Test
    void premiumsPaidMatchesRawPolicyTransactionsWithComparison() {
        ComputedAnswer a = run(QueryPlan.of(Intent.PREMIUMS_PAID).withPeriod(Q3).withCompare(true));
        BigDecimal q3 = rawSum("insurance_policies", "txnDate", Q3, t -> true);
        BigDecimal q2 = rawSum("insurance_policies", "txnDate", Q2, t -> true);
        assertThat(a.figure("total").value()).isEqualByComparingTo(q3);
        assertThat(a.figure("previousTotal").value()).isEqualByComparingTo(q2);
        assertThat(a.figure("change").value()).isEqualByComparingTo(q3.subtract(q2));
    }

    @Test
    void spendingExcludesSipDebitsButIncludesPremiumDebits() {
        ComputedAnswer a = run(QueryPlan.of(Intent.SPEND_TOTAL));
        BigDecimal expected = rawSum("deposit", "transactionTimestamp", null,
                t -> t.get("type").asText().equals("DEBIT") && !t.get("narration").asText().startsWith("ACH/SIP/")
                        && !t.get("narration").asText().startsWith("NEFT/DEMAT/"));
        assertThat(a.figure("total").value()).isEqualByComparingTo(expected);
        assertThat(a.citations()).noneMatch(r -> r.category() == Category.SIP_DEBIT);
        assertThat(a.breakdown()).anyMatch(b -> b.label().equals("Premium debit"));
    }

    @Test
    void netWorthIsTheSumOfReportedAccountValuesExcludingInsurance() {
        ComputedAnswer a = run(QueryPlan.of(Intent.BALANCE));
        BigDecimal expected = BigDecimal.ZERO;
        for (SourceAccount s : raw) {
            JsonNode summary = s.account().get("summary");
            switch (s.account().get("type").asText()) {
                case "deposit", "epf" -> expected = expected.add(new BigDecimal(summary.get("currentBalance").asText()));
                case "mutual_funds", "equities" -> expected = expected.add(new BigDecimal(summary.get("currentValue").asText()));
                default -> { }
            }
        }
        assertThat(a.figure("total").value()).isEqualByComparingTo(expected);
        assertThat(a.citations()).hasSize(4).noneMatch(r -> r.fiType() == FiType.INSURANCE_POLICIES);
    }

    @Test
    void epfSharesAddUpToTheTotal() {
        ComputedAnswer a = run(QueryPlan.of(Intent.EPF_CONTRIBUTIONS));
        assertThat(a.figure("employeeShare").value().add(a.figure("employerShare").value()))
                .isEqualByComparingTo(a.figure("total").value());
        assertThat(a.figure("count").value()).isEqualByComparingTo("11");
    }

    @Test
    void topExpensesAreTheLargestSpendsInOrder() {
        ComputedAnswer a = run(QueryPlan.of(Intent.TOP_EXPENSES).withPeriod(Q3).withTopN(3));
        assertThat(a.citations()).hasSize(3);
        assertThat(a.citations().get(0).amount()).isGreaterThanOrEqualTo(a.citations().get(1).amount());
        BigDecimal largestRaw = records.stream()
                .filter(r -> r.fiType() == FiType.DEPOSIT && Category.SPENDING.contains(r.category()) && Q3.contains(r.date()))
                .map(FinancialRecord::amount).max(BigDecimal::compareTo).orElseThrow();
        assertThat(a.citations().get(0).amount()).isEqualByComparingTo(largestRaw);
    }

    @Test
    void computeIgnoresRecordsRetrievalShouldNotHaveReturned() {
        QueryPlan plan = QueryPlan.of(Intent.SPEND_CATEGORY).withCategories(Set.of(Category.FUEL)).withPeriod(Q3);
        ComputedAnswer exact = run(plan);
        ComputedAnswer sloppy = engine.compute(plan, records);
        assertThat(sloppy.figure("total").value()).isEqualByComparingTo(exact.figure("total").value());
        assertThat(sloppy.citations()).isEqualTo(exact.citations());
    }

    @Test
    void unsupportedQuestionsCiteNothing() {
        ComputedAnswer a = engine.compute(QueryPlan.of(Intent.UNSUPPORTED), records);
        assertThat(a.empty()).isTrue();
        assertThat(a.figures()).isEmpty();
    }

    @Test
    void savingsRateIsSalaryMinusSpendingOverSalary() {
        ComputedAnswer a = run(QueryPlan.of(Intent.SAVINGS_RATE).withPeriod(Q3));
        BigDecimal salary = rawSum("deposit", "transactionTimestamp", Q3, t -> t.get("narration").asText().contains("SALARY"));
        BigDecimal spent = rawSum("deposit", "transactionTimestamp", Q3,
                t -> t.get("type").asText().equals("DEBIT") && !t.get("narration").asText().startsWith("ACH/SIP/")
                        && !t.get("narration").asText().startsWith("NEFT/DEMAT/"));
        assertThat(a.figure("income").value()).isEqualByComparingTo(salary);
        assertThat(a.figure("spending").value()).isEqualByComparingTo(spent);
        assertThat(a.figure("saved").value()).isEqualByComparingTo(salary.subtract(spent));
        assertThat(a.figure("savingsRate").value()).isEqualByComparingTo(
                salary.subtract(spent).multiply(BigDecimal.valueOf(100)).divide(salary, 1, java.math.RoundingMode.HALF_UP));
        assertThat(a.citations()).noneMatch(r -> r.category() == Category.SIP_DEBIT || r.category() == Category.INVESTMENT_DEBIT);
    }

    @Test
    void merchantsAreGroupedByPayeeAndLimitedToTopN() {
        ComputedAnswer a = run(QueryPlan.of(Intent.TOP_MERCHANTS).withPeriod(Q3).withTopN(3));
        BigDecimal spent = rawSum("deposit", "transactionTimestamp", Q3,
                t -> t.get("type").asText().equals("DEBIT") && !t.get("narration").asText().startsWith("ACH/SIP/")
                        && !t.get("narration").asText().startsWith("NEFT/DEMAT/"));
        assertThat(a.figure("total").value()).isEqualByComparingTo(spent);
        assertThat(a.breakdown()).hasSize(3);
        assertThat(a.breakdown().get(0).value()).isGreaterThanOrEqualTo(a.breakdown().get(1).value());
        assertThat(a.breakdown().get(1).value()).isGreaterThanOrEqualTo(a.breakdown().get(2).value());
        assertThat(a.figure("merchants").value().intValue()).isGreaterThan(3);
    }

    @Test
    void spendTrendHasOneRowPerMonthAndAnAverage() {
        ComputedAnswer a = run(QueryPlan.of(Intent.SPEND_TREND));
        assertThat(a.breakdown()).hasSize(12);
        BigDecimal months = a.breakdown().stream().map(BreakdownItem::value).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(months).isEqualByComparingTo(a.figure("total").value());
        assertThat(a.figure("monthlyAverage").value()).isEqualByComparingTo(
                a.figure("total").value().divide(BigDecimal.valueOf(12), 2, java.math.RoundingMode.HALF_UP));
    }

    @Test
    void calendarRangesStepBackByWholeMonths() {
        assertThat(Q3.previous().from()).isEqualTo(LocalDate.of(2026, 4, 1));
        assertThat(Q3.previous().to()).isEqualTo(LocalDate.of(2026, 6, 30));
    }

    private BigDecimal rawSum(String type, String dateField, DateRange range, Predicate<JsonNode> keep) {
        BigDecimal total = BigDecimal.ZERO;
        for (SourceAccount s : raw) {
            if (!s.account().get("type").asText().equals(type)) {
                continue;
            }
            for (JsonNode t : s.account().at("/transactions/transaction")) {
                LocalDate d = LocalDate.parse(t.get(dateField).asText().substring(0, 10));
                if ((range == null || range.contains(d)) && keep.test(t)) {
                    total = total.add(new BigDecimal(t.get("amount").asText()));
                }
            }
        }
        return total;
    }
}
