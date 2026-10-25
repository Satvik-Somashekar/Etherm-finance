package com.openfinance.core.compute;

import com.openfinance.core.compute.Figure.Unit;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * The deterministic compute layer. Given a plan and the records retrieval returned, it re-applies
 * the plan's own filter (it does not trust retrieval to be exact), then sums, counts, groups and
 * ranks in {@link BigDecimal}. Every figure carries the ids of the records it was computed from.
 */
public class QueryEngine {

    private static final Set<FiType> ASSET_TYPES =
            EnumSet.of(FiType.DEPOSIT, FiType.MUTUAL_FUNDS, FiType.EPF, FiType.EQUITIES);

    /** What retrieval must return for this plan. Includes the preceding period when comparing. */
    public RecordFilter retrievalFilter(QueryPlan plan) {
        RecordFilter current = filterFor(plan, plan.period());
        if (!plan.compare() || plan.period() == null) {
            return current;
        }
        return new RecordFilter(current.fiTypes(), current.kinds(), current.categories(), current.direction(),
                plan.period().previous().from(), current.to());
    }

    public ComputedAnswer compute(QueryPlan plan, List<FinancialRecord> retrieved) {
        RecordFilter filter = filterFor(plan, plan.period());
        List<FinancialRecord> records = retrieved.stream().filter(filter::matches)
                .sorted(Comparator.comparing(FinancialRecord::date).thenComparing(FinancialRecord::recordId))
                .toList();

        return switch (plan.intent()) {
            case SPEND_TOTAL -> flow(plan, records, retrieved, "Total spent", r -> label(r.category()));
            case SPEND_CATEGORY -> flow(plan, records, retrieved, "Spent", r -> nonNull(r.counterparty(), label(r.category())));
            case INCOME -> flow(plan, records, retrieved, "Received", r -> YearMonth.from(r.date()).toString());
            case PREMIUMS_PAID -> flow(plan, records, retrieved, "Premiums paid", r -> r.attributes().getOrDefault("policyName", r.description()));
            case MF_INVESTED -> flow(plan, records, retrieved, "Invested", FinancialRecord::description);
            case EPF_CONTRIBUTIONS -> epf(plan, records, retrieved);
            case TOP_EXPENSES -> top(plan, records);
            case SAVINGS_RATE -> savings(plan, records, retrieved);
            case TOP_MERCHANTS -> merchants(plan, records, retrieved);
            case SPEND_TREND -> trend(plan, records, retrieved);
            case BALANCE -> snapshot(plan, records, plan.fiTypes().isEmpty() || plan.fiTypes().containsAll(ASSET_TYPES)
                    ? "Net worth" : "Current value", r -> accountLabel(r));
            case HOLDINGS -> snapshot(plan, records, "Holdings value", FinancialRecord::description);
            case INSURANCE_COVER -> snapshot(plan, records, "Total sum assured", r -> r.attributes().getOrDefault("policyName", r.description()));
            case UNSUPPORTED -> new ComputedAnswer(plan, List.of(), List.of(), List.of(), 0);
        };
    }

    // ------------------------------------------------------------------ filters

    private RecordFilter filterFor(QueryPlan plan, DateRange period) {
        LocalDate from = period == null ? null : period.from();
        LocalDate to = period == null ? null : period.to();
        Set<RecordKind> tx = Set.of(RecordKind.TRANSACTION);
        return switch (plan.intent()) {
            case SPEND_TOTAL, TOP_EXPENSES -> new RecordFilter(Set.of(FiType.DEPOSIT), tx,
                    plan.categories().isEmpty() ? Category.SPENDING : plan.categories(), Direction.DEBIT, from, to);
            case SPEND_CATEGORY -> new RecordFilter(Set.of(FiType.DEPOSIT), tx, plan.categories(), Direction.DEBIT, from, to);
            case TOP_MERCHANTS, SPEND_TREND -> new RecordFilter(Set.of(FiType.DEPOSIT), tx, Category.SPENDING, Direction.DEBIT, from, to);
            case SAVINGS_RATE -> {
                Set<Category> both = EnumSet.copyOf(Category.SPENDING);
                both.add(Category.SALARY);
                yield new RecordFilter(Set.of(FiType.DEPOSIT), tx, both, null, from, to);
            }
            case INCOME -> new RecordFilter(Set.of(FiType.DEPOSIT), tx,
                    plan.categories().isEmpty() ? Set.of(Category.SALARY) : plan.categories(), Direction.CREDIT, from, to);
            case PREMIUMS_PAID -> new RecordFilter(Set.of(FiType.INSURANCE_POLICIES), tx,
                    Set.of(Category.PREMIUM_PAYMENT), null, from, to);
            case MF_INVESTED -> new RecordFilter(Set.of(FiType.MUTUAL_FUNDS), tx, Set.of(Category.MF_PURCHASE), null, from, to);
            case EPF_CONTRIBUTIONS -> new RecordFilter(Set.of(FiType.EPF), tx, Set.of(Category.EPF_CONTRIBUTION), null, from, to);
            case BALANCE -> new RecordFilter(plan.fiTypes().isEmpty() ? ASSET_TYPES : plan.fiTypes(),
                    Set.of(RecordKind.BALANCE), Set.of(), null, null, null);
            case HOLDINGS -> new RecordFilter(plan.fiTypes().isEmpty() ? Set.of(FiType.MUTUAL_FUNDS, FiType.EQUITIES)
                    : plan.fiTypes(), Set.of(RecordKind.HOLDING), Set.of(), null, null, null);
            case INSURANCE_COVER -> new RecordFilter(Set.of(FiType.INSURANCE_POLICIES), Set.of(RecordKind.POLICY),
                    Set.of(), null, null, null);
            // Matches nothing, so an unsupported question never retrieves or cites data.
            case UNSUPPORTED -> new RecordFilter(Set.of(), Set.of(RecordKind.TRANSACTION), Set.of(), null,
                    LocalDate.MAX, LocalDate.MIN);
        };
    }

    // ------------------------------------------------------------- calculations

    private ComputedAnswer flow(QueryPlan plan, List<FinancialRecord> records, List<FinancialRecord> retrieved,
                                String totalLabel, Function<FinancialRecord, String> groupBy) {
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("total", totalLabel, sum(records), Unit.INR, ids(records)));
        figures.add(new Figure("count", "Transactions", BigDecimal.valueOf(records.size()), Unit.COUNT, ids(records)));
        List<FinancialRecord> cited = new ArrayList<>(records);
        addComparison(plan, records, retrieved, figures, cited);
        return new ComputedAnswer(plan, figures, groupSum(records, groupBy), cited, retrieved.size());
    }

    private ComputedAnswer epf(QueryPlan plan, List<FinancialRecord> records, List<FinancialRecord> retrieved) {
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("total", "Contributions credited", sum(records), Unit.INR, ids(records)));
        figures.add(new Figure("employeeShare", "Employee share", sumAttribute(records, "employeeShare"), Unit.INR, ids(records)));
        figures.add(new Figure("employerShare", "Employer share", sumAttribute(records, "employerShare"), Unit.INR, ids(records)));
        figures.add(new Figure("count", "Monthly credits", BigDecimal.valueOf(records.size()), Unit.COUNT, ids(records)));
        List<FinancialRecord> cited = new ArrayList<>(records);
        addComparison(plan, records, retrieved, figures, cited);
        return new ComputedAnswer(plan, figures,
                groupSum(records, r -> r.attributes().getOrDefault("wageMonth", YearMonth.from(r.date()).toString())),
                cited, retrieved.size());
    }

    private void addComparison(QueryPlan plan, List<FinancialRecord> current, List<FinancialRecord> retrieved,
                               List<Figure> figures, List<FinancialRecord> cited) {
        if (!plan.compare() || plan.period() == null) {
            return;
        }
        DateRange prev = plan.period().previous();
        RecordFilter prevFilter = filterFor(plan, prev);
        List<FinancialRecord> before = retrieved.stream().filter(prevFilter::matches).toList();
        BigDecimal prevTotal = sum(before);
        BigDecimal change = sum(current).subtract(prevTotal);
        List<String> both = new ArrayList<>(ids(current));
        both.addAll(ids(before));
        figures.add(new Figure("previousTotal", "Previous period (" + prev.from() + " to " + prev.to() + ")",
                prevTotal, Unit.INR, ids(before)));
        figures.add(new Figure("change", "Change", change, Unit.INR, both));
        if (prevTotal.signum() != 0) {
            figures.add(new Figure("changePct", "Change", change.multiply(BigDecimal.valueOf(100))
                    .divide(prevTotal, 1, RoundingMode.HALF_UP), Unit.PERCENT, both));
        }
        cited.addAll(before);
    }

    private ComputedAnswer top(QueryPlan plan, List<FinancialRecord> records) {
        List<FinancialRecord> top = records.stream()
                .sorted(Comparator.comparing(FinancialRecord::amount).reversed().thenComparing(FinancialRecord::recordId))
                .limit(plan.topN())
                .toList();
        List<BreakdownItem> items = top.stream()
                .map(r -> new BreakdownItem(r.date() + " " + nonNull(r.counterparty(), r.description()), r.amount(),
                        List.of(r.recordId())))
                .toList();
        List<Figure> figures = List.of(
                new Figure("total", "Top " + top.size() + " spends", sum(top), Unit.INR, ids(top)),
                new Figure("count", "Spends considered", BigDecimal.valueOf(records.size()), Unit.COUNT, ids(records)));
        return new ComputedAnswer(plan, figures, items, top, records.size());
    }

    /** Salary received minus spending, as a share of salary. Spending is debits only; salary is credits only. */
    private ComputedAnswer savings(QueryPlan plan, List<FinancialRecord> records, List<FinancialRecord> retrieved) {
        List<FinancialRecord> income = records.stream().filter(r -> r.category() == Category.SALARY).toList();
        List<FinancialRecord> spending = records.stream()
                .filter(r -> r.direction() == Direction.DEBIT && Category.SPENDING.contains(r.category())).toList();
        BigDecimal earned = sum(income);
        BigDecimal spent = sum(spending);
        BigDecimal saved = earned.subtract(spent);
        List<FinancialRecord> used = new ArrayList<>(income);
        used.addAll(spending);
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("income", "Salary received", earned, Unit.INR, ids(income)));
        figures.add(new Figure("spending", "Spent", spent, Unit.INR, ids(spending)));
        figures.add(new Figure("saved", "Left over", saved, Unit.INR, ids(used)));
        if (earned.signum() > 0) {
            figures.add(new Figure("savingsRate", "Savings rate", saved.multiply(BigDecimal.valueOf(100))
                    .divide(earned, 1, RoundingMode.HALF_UP), Unit.PERCENT, ids(used)));
        }
        List<BreakdownItem> items = List.of(
                new BreakdownItem("Salary received", earned, ids(income)),
                new BreakdownItem("Spent", spent, ids(spending)));
        return new ComputedAnswer(plan, figures, items, used, retrieved.size());
    }

    /** Spending grouped by payee, largest first; the breakdown keeps the top N, the total covers all. */
    private ComputedAnswer merchants(QueryPlan plan, List<FinancialRecord> records, List<FinancialRecord> retrieved) {
        List<BreakdownItem> all = groupSum(records, r -> payeeName(nonNull(r.counterparty(), label(r.category()))));
        List<Figure> figures = List.of(
                new Figure("total", "Total spent", sum(records), Unit.INR, ids(records)),
                new Figure("count", "Transactions", BigDecimal.valueOf(records.size()), Unit.COUNT, ids(records)),
                new Figure("merchants", "Payees", BigDecimal.valueOf(all.size()), Unit.COUNT, ids(records)));
        return new ComputedAnswer(plan, figures, all.stream().limit(plan.topN()).toList(), records, retrieved.size());
    }

    /** Spending per calendar month, with the average over the months that had any spending. */
    private ComputedAnswer trend(QueryPlan plan, List<FinancialRecord> records, List<FinancialRecord> retrieved) {
        List<BreakdownItem> months = groupSum(records, r -> monthName(YearMonth.from(r.date())));
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("total", "Total spent", sum(records), Unit.INR, ids(records)));
        figures.add(new Figure("count", "Transactions", BigDecimal.valueOf(records.size()), Unit.COUNT, ids(records)));
        figures.add(new Figure("months", "Months with spending", BigDecimal.valueOf(months.size()), Unit.COUNT, ids(records)));
        if (!months.isEmpty()) {
            figures.add(new Figure("monthlyAverage", "Average per month", sum(records)
                    .divide(BigDecimal.valueOf(months.size()), 2, RoundingMode.HALF_UP), Unit.INR, ids(records)));
        }
        return new ComputedAnswer(plan, figures, months, records, retrieved.size());
    }

    /** "FRESHMART" -> "Freshmart"; names already in mixed case are left alone. */
    private static String payeeName(String name) {
        return name.equals(name.toUpperCase(Locale.ROOT)) && name.length() > 1
                ? name.charAt(0) + name.substring(1).toLowerCase(Locale.ROOT) : name;
    }

    private static String monthName(YearMonth ym) {
        return ym.getMonth().getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
    }

    private ComputedAnswer snapshot(QueryPlan plan, List<FinancialRecord> records, String totalLabel,
                                    Function<FinancialRecord, String> groupBy) {
        List<FinancialRecord> scoped = records;
        if (plan.intent() == Intent.INSURANCE_COVER && plan.subject() != null) {
            scoped = records.stream().filter(r -> plan.subject().equalsIgnoreCase(r.attributes().get("policyType"))).toList();
        }
        List<Figure> figures = List.of(
                new Figure("total", totalLabel, sum(scoped), Unit.INR, ids(scoped)),
                new Figure("count", plan.intent() == Intent.HOLDINGS ? "Holdings" : "Accounts",
                        BigDecimal.valueOf(scoped.size()), Unit.COUNT, ids(scoped)));
        return new ComputedAnswer(plan, figures, groupSum(scoped, groupBy), scoped, records.size());
    }

    // ------------------------------------------------------------------ helpers

    static BigDecimal sum(List<FinancialRecord> records) {
        return records.stream().map(FinancialRecord::amount).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal sumAttribute(List<FinancialRecord> records, String attribute) {
        return records.stream().map(r -> new BigDecimal(r.attributes().getOrDefault(attribute, "0")))
                .reduce(BigDecimal.ZERO, BigDecimal::add).setScale(2, RoundingMode.HALF_UP);
    }

    /** Groups by label, sums each group, and orders groups by value, largest first. */
    private static List<BreakdownItem> groupSum(List<FinancialRecord> records, Function<FinancialRecord, String> groupBy) {
        Map<String, List<FinancialRecord>> groups = new LinkedHashMap<>();
        for (FinancialRecord r : records) {
            groups.computeIfAbsent(groupBy.apply(r), k -> new ArrayList<>()).add(r);
        }
        return groups.entrySet().stream()
                .map(e -> new BreakdownItem(e.getKey(), sum(e.getValue()), ids(e.getValue())))
                .sorted(Comparator.comparing(BreakdownItem::value).reversed().thenComparing(BreakdownItem::label))
                .toList();
    }

    private static List<String> ids(List<FinancialRecord> records) {
        return records.stream().map(FinancialRecord::recordId).toList();
    }

    private static String accountLabel(FinancialRecord r) {
        return r.description() + " (" + r.accountId() + ")";
    }

    static String label(Category c) {
        String s = c.name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String nonNull(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
