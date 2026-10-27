package com.openfinance.rag;

import com.openfinance.core.compute.DateRange;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class QuestionParserTest {

    private static final LocalDate AS_OF = LocalDate.of(2026, 10, 25);
    private final QuestionParser parser = new QuestionParser();

    private QueryPlan parse(String q) {
        return parser.parse(q, AS_OF);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "How much did I spend on groceries last quarter?|SPEND_CATEGORY",
            "I want you to compare my expenses and income|SAVINGS_RATE",
            "What did I spend in total last month?|SPEND_TOTAL",
            "Show my 3 biggest expenses this year|TOP_EXPENSES",
            "How much salary did I receive since April?|INCOME",
            "How much did I pay in insurance premiums last quarter?|PREMIUMS_PAID",
            "How much have I invested in mutual funds through SIPs?|MF_INVESTED",
            "What were my EPF contributions this financial year?|EPF_CONTRIBUTIONS",
            "What is my EPF balance?|BALANCE",
            "What's my net worth?|BALANCE",
            "How much is in my savings account?|BALANCE",
            "Which stocks do I hold?|HOLDINGS",
            "What is my life cover?|INSURANCE_COVER",
            "Should I buy a house?|UNSUPPORTED",
            // Gaps found by evaluation/evaluate.py
            "How much money went out of my account last month?|SPEND_TOTAL",
            "How much money came in last quarter?|INCOME",
            "What did my insurance cost in FY 2025-26?|PREMIUMS_PAID",
            "What is my stock portfolio worth?|BALANCE",
            "Should I buy more Infosys shares?|UNSUPPORTED",
            "Is now a good time to invest in mutual funds?|UNSUPPORTED",
            // Wider questions
            "What is my savings rate this year?|SAVINGS_RATE",
            "How much did I save last quarter?|SAVINGS_RATE",
            "Where do I spend the most money?|TOP_MERCHANTS",
            "Show my top 3 merchants last month|TOP_MERCHANTS",
            "What is my average monthly spending?|SPEND_TREND",
            "Show my spending month by month|SPEND_TREND",
    })
    void recognisesIntent(String question, Intent expected) {
        assertThat(parse(question).intent()).isEqualTo(expected);
    }

    @Test
    void portfolioIsNotAMutualFundFolio() {
        assertThat(parse("What is my stock portfolio worth?").fiTypes()).containsExactly(FiType.EQUITIES);
    }

    @Test
    void categoryWordsMapToCategories() {
        assertThat(parse("How much did I spend on food and fuel in August?").categories())
                .containsExactlyInAnyOrder(Category.FOOD, Category.FUEL);
        assertThat(parse("How much rent did I pay last quarter?").categories()).containsExactly(Category.RENT);
    }

    @Test
    void relativePeriodsResolveAgainstAsOf() {
        assertThat(range("spend last quarter")).isEqualTo(range(2026, 7, 1, 2026, 9, 30));
        assertThat(range("spend last month")).isEqualTo(range(2026, 9, 1, 2026, 9, 30));
        assertThat(range("spend in the last 3 months")).isEqualTo(range(2026, 7, 1, 2026, 9, 30));
        assertThat(range("spend this financial year")).isEqualTo(range(2026, 4, 1, 2026, 10, 25));
        assertThat(range("spend in FY 2025-26")).isEqualTo(range(2025, 4, 1, 2026, 3, 31));
        assertThat(range("spend this year")).isEqualTo(range(2026, 1, 1, 2026, 10, 25));
    }

    @Test
    void namedMonthsPickTheMostRecentOccurrence() {
        assertThat(range("spend in december")).isEqualTo(range(2025, 12, 1, 2025, 12, 31));
        assertThat(range("spend in march 2026")).isEqualTo(range(2026, 3, 1, 2026, 3, 31));
        assertThat(range("spend between november and february")).isEqualTo(range(2025, 11, 1, 2026, 2, 28));
        assertThat(range("spend since june")).isEqualTo(range(2026, 6, 1, 2026, 10, 25));
    }

    @Test
    void comparisonWordsTurnOnComparison() {
        QueryPlan p = parse("Did my insurance premiums increase last quarter compared to the one before?");
        assertThat(p.compare()).isTrue();
        assertThat(parse("premiums last quarter").compare()).isFalse();
    }

    @Test
    void balancesIgnorePeriodsAndCarryFiTypes() {
        QueryPlan p = parse("What was my mutual fund value last month?");
        assertThat(p.intent()).isEqualTo(Intent.BALANCE);
        assertThat(p.period()).isNull();
        assertThat(p.fiTypes()).isEqualTo(Set.of(FiType.MUTUAL_FUNDS));
    }

    @Test
    void topNIsRead() {
        assertThat(parse("top 3 expenses last month").topN()).isEqualTo(3);
        assertThat(parse("largest expenses").topN()).isEqualTo(5);
    }

    private DateRange range(String q) {
        DateRange r = parse(q).period();
        return new DateRange(r.from(), r.to(), "");
    }

    private static DateRange range(int y1, int m1, int d1, int y2, int m2, int d2) {
        return new DateRange(LocalDate.of(y1, m1, d1), LocalDate.of(y2, m2, d2), "");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "Export my transactions|EXPORT_TRANSACTIONS",
            "Download my transactions for last quarter as CSV|EXPORT_TRANSACTIONS",
            "Can I get a spreadsheet of my grocery spending in March 2026|EXPORT_TRANSACTIONS",
            "Generate a monthly report|EXPORT_REPORT",
            "Create a report of my spending for FY 2025-26|EXPORT_REPORT",
            "Download a summary of this year|EXPORT_REPORT",
            "Give me my bank statement|EXPORT_TRANSACTIONS",
            "How much did I save last month?|SAVINGS_RATE",
            "What was my spending report like?|SPEND_TOTAL",
    })
    void exportRequestsAreRecognised(String question, Intent expected) {
        assertThat(parse(question).intent()).isEqualTo(expected);
    }

    @Test
    void exportKeepsItsPeriodAndCategoriesButNeverComparesPeriods() {
        QueryPlan plan = parse("Export my grocery and salary transactions for last quarter, change since before");
        assertThat(plan.intent()).isEqualTo(Intent.EXPORT_TRANSACTIONS);
        assertThat(plan.categories()).containsExactlyInAnyOrder(Category.GROCERIES, Category.SALARY);
        assertThat(plan.period().from()).isEqualTo(LocalDate.of(2026, 7, 1));
        assertThat(plan.compare()).isFalse();
    }
}
