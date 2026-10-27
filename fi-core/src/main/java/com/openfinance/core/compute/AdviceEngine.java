package com.openfinance.core.compute;

import com.openfinance.core.compute.Figure.Unit;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The numbers behind a suggestion about one part of the customer's money: insurance, mutual funds, stocks,
 * EPF, or the overall mix. Everything is computed in {@link BigDecimal} from the retrieved records. The rules
 * of thumb used to frame a result (cover as a multiple of income, a concentration flag, an emergency cushion)
 * are returned as figures too, so they are visible as assumptions and never typed into a sentence.
 *
 * <p>Headline figures come first; breakdown item order is described on each method.
 */
final class AdviceEngine {

    /** Common starting points, not advice: life cover of 10 to 15 times yearly income. */
    private static final BigDecimal COVER_LOW = BigDecimal.TEN;
    private static final BigDecimal COVER_HIGH = BigDecimal.valueOf(15);
    private static final BigDecimal FUND_CONCENTRATION = BigDecimal.valueOf(40);
    private static final BigDecimal STOCK_CONCENTRATION = BigDecimal.valueOf(25);
    private static final BigDecimal EMERGENCY_MONTHS = BigDecimal.valueOf(6);
    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private AdviceEngine() {
    }

    /** What retrieval must return for this domain. Point-in-time records have old dates, so no period applies. */
    static RecordFilter filter(QueryPlan plan, AdviceDomain domain, Set<FiType> assetTypes) {
        boolean deposits = plan.fiTypes().isEmpty() || plan.fiTypes().contains(FiType.DEPOSIT);
        return switch (domain) {
            case INSURANCE -> new RecordFilter(deposits ? Set.of(FiType.INSURANCE_POLICIES, FiType.DEPOSIT) : Set.of(FiType.INSURANCE_POLICIES),
                    Set.of(RecordKind.POLICY, RecordKind.TRANSACTION),
                    Set.of(Category.POLICY_COVER, Category.PREMIUM_PAYMENT, Category.SALARY), null, null, null);
            case MUTUAL_FUNDS -> new RecordFilter(Set.of(FiType.MUTUAL_FUNDS),
                    Set.of(RecordKind.HOLDING, RecordKind.BALANCE, RecordKind.TRANSACTION),
                    Set.of(Category.HOLDING_VALUE, Category.ACCOUNT_BALANCE, Category.MF_PURCHASE), null, null, null);
            case EQUITIES -> new RecordFilter(Set.of(FiType.EQUITIES), Set.of(RecordKind.HOLDING),
                    Set.of(Category.HOLDING_VALUE), null, null, null);
            case EPF -> new RecordFilter(Set.of(FiType.EPF), Set.of(RecordKind.BALANCE, RecordKind.TRANSACTION),
                    Set.of(Category.ACCOUNT_BALANCE, Category.EPF_CONTRIBUTION, Category.EPF_INTEREST), null, null, null);
            case OVERALL -> {
                java.util.EnumSet<Category> cats = java.util.EnumSet.copyOf(Category.SPENDING);
                cats.add(Category.ACCOUNT_BALANCE);
                yield new RecordFilter(plan.fiTypes().isEmpty() ? assetTypes : plan.fiTypes(),
                        Set.of(RecordKind.BALANCE, RecordKind.TRANSACTION), cats, null, null, null);
            }
            case SPENDING -> throw new IllegalArgumentException("spending is handled by the query engine");
        };
    }

    static ComputedAnswer compute(QueryPlan plan, AdviceDomain domain, List<FinancialRecord> records, int scanned) {
        ComputedAnswer answer = switch (domain) {
            case INSURANCE -> insurance(plan, records, scanned);
            case MUTUAL_FUNDS -> mutualFunds(plan, records, scanned);
            case EQUITIES -> equities(plan, records, scanned);
            case EPF -> epf(plan, records, scanned);
            case OVERALL -> overall(plan, records, scanned);
            case SPENDING -> throw new IllegalArgumentException("spending is handled by the query engine");
        };
        return answer;
    }

    // ---------------------------------------------------------------- insurance

    /** Items: one per policy, by sum assured. */
    private static ComputedAnswer insurance(QueryPlan plan, List<FinancialRecord> records, int scanned) {
        List<FinancialRecord> policies = of(records, RecordKind.POLICY);
        if (policies.isEmpty()) {
            return empty(plan, scanned);
        }
        BigDecimal cover = QueryEngine.sum(policies);
        BigDecimal term = QueryEngine.sum(policies.stream().filter(p -> "TERM".equals(p.attributes().get("policyType"))).toList());
        BigDecimal health = QueryEngine.sum(policies.stream().filter(p -> "HEALTH".equals(p.attributes().get("policyType"))).toList());
        BigDecimal premium = policies.stream().map(AdviceEngine::annualPremium).reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(2, RoundingMode.HALF_UP);

        List<FinancialRecord> used = new ArrayList<>(policies);
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("totalCover", "Total cover (sum assured)", cover, Unit.INR, ids(policies)));
        figures.add(new Figure("annualPremium", "Premiums per year", premium, Unit.INR, ids(policies)));
        figures.add(new Figure("premiumRate", "Premium as a share of cover", ratio(premium, cover, 2), Unit.PERCENT, ids(policies)));
        figures.add(new Figure("termCover", "Term life cover", term, Unit.INR, ids(policies)));
        figures.add(new Figure("healthCover", "Health cover", health, Unit.INR, ids(policies)));

        List<FinancialRecord> salaries = latestSalaries(records);
        BigDecimal income = yearlyIncome(salaries);
        if (income.signum() > 0) {
            used.addAll(salaries);
            BigDecimal target = income.multiply(COVER_LOW).setScale(2, RoundingMode.HALF_UP);
            figures.add(new Figure("annualIncome", "Yearly salary (latest credits, annualised)", income, Unit.INR, ids(salaries)));
            figures.add(new Figure("termMultiple", "Term cover as a multiple of yearly salary",
                    term.divide(income, 1, RoundingMode.HALF_UP), Unit.COUNT, ids(used)));
            figures.add(new Figure("coverRuleLow", "Rule of thumb, low end (times yearly income)", COVER_LOW, Unit.COUNT, List.of()));
            figures.add(new Figure("coverRuleHigh", "Rule of thumb, high end (times yearly income)", COVER_HIGH, Unit.COUNT, List.of()));
            figures.add(new Figure("termShortfall", "Term cover short of the low end", target.subtract(term).max(BigDecimal.ZERO), Unit.INR, ids(used)));
            figures.add(new Figure("premiumShare", "Premiums as a share of salary", ratio(premium, income, 1), Unit.PERCENT, ids(used)));
        }
        List<BreakdownItem> items = policies.stream()
                .sorted(Comparator.comparing(FinancialRecord::amount).reversed())
                .map(p -> new BreakdownItem(p.attributes().getOrDefault("policyName", p.description()), p.amount(), List.of(p.recordId())))
                .toList();
        return new ComputedAnswer(plan, figures, items, used, scanned);
    }

    /** Premium per year from the policy's own amount and frequency. */
    private static BigDecimal annualPremium(FinancialRecord policy) {
        BigDecimal amount = new BigDecimal(policy.attributes().getOrDefault("premiumAmount", "0"));
        String frequency = policy.attributes().getOrDefault("premiumFrequency", "ANNUAL").toUpperCase(Locale.ROOT);
        int perYear = switch (frequency) {
            case "MONTHLY" -> 12;
            case "QUARTERLY" -> 4;
            case "HALF_YEARLY", "HALFYEARLY", "SEMI_ANNUAL" -> 2;
            default -> 1;
        };
        return amount.multiply(BigDecimal.valueOf(perYear));
    }

    // ------------------------------------------------------------- mutual funds

    /** Items: 1 largest scheme, 2 largest scheme type (value of the type), then the other schemes. */
    private static ComputedAnswer mutualFunds(QueryPlan plan, List<FinancialRecord> records, int scanned) {
        List<FinancialRecord> holdings = of(records, RecordKind.HOLDING);
        if (holdings.isEmpty()) {
            return empty(plan, scanned);
        }
        List<FinancialRecord> summaries = of(records, RecordKind.BALANCE);
        List<FinancialRecord> byValue = holdings.stream().sorted(Comparator.comparing(FinancialRecord::amount).reversed()).toList();
        BigDecimal total = QueryEngine.sum(holdings);
        FinancialRecord top = byValue.get(0);

        List<FinancialRecord> used = new ArrayList<>(holdings);
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("total", "Mutual fund value", total, Unit.INR, ids(holdings)));
        BigDecimal cost = summaries.stream().map(r -> new BigDecimal(r.attributes().getOrDefault("costValue", "0")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        if (cost.signum() > 0) {
            used.addAll(summaries);
            BigDecimal gain = total.subtract(cost);
            figures.add(new Figure(gain.signum() >= 0 ? "gain" : "loss", gain.signum() >= 0 ? "Gain over cost" : "Loss against cost",
                    gain.abs().setScale(2, RoundingMode.HALF_UP), Unit.INR, ids(used)));
            figures.add(new Figure("gainPct", "Change over cost", ratio(gain.abs(), cost, 1), Unit.PERCENT, ids(used)));
        }
        figures.add(new Figure("topShare", "Largest scheme's share of the portfolio", ratio(top.amount(), total, 1), Unit.PERCENT, List.of(top.recordId())));
        figures.add(new Figure("count", "Schemes", BigDecimal.valueOf(holdings.size()), Unit.COUNT, ids(holdings)));
        figures.add(new Figure("concThreshold", "Concentration flag (illustrative)", FUND_CONCENTRATION, Unit.PERCENT, List.of()));

        Map<String, List<FinancialRecord>> types = holdings.stream()
                .collect(Collectors.groupingBy(h -> title(h.attributes().getOrDefault("schemeCategory", "Other"))));
        Map.Entry<String, List<FinancialRecord>> topType = types.entrySet().stream()
                .max(Comparator.comparing(e -> QueryEngine.sum(e.getValue()))).orElseThrow();
        figures.add(new Figure("topTypeShare", "Largest scheme type's share", ratio(QueryEngine.sum(topType.getValue()), total, 1), Unit.PERCENT, ids(topType.getValue())));

        List<FinancialRecord> purchases = latestMonths(of(records, Category.MF_PURCHASE), 3);
        if (!purchases.isEmpty()) {
            used.addAll(purchases);
            figures.add(new Figure("monthlySip", "Average invested per month (recent months)", monthlyAverage(purchases), Unit.INR, ids(purchases)));
        }

        List<BreakdownItem> items = new ArrayList<>();
        items.add(new BreakdownItem(top.description(), top.amount(), List.of(top.recordId())));
        items.add(new BreakdownItem(topType.getKey(), QueryEngine.sum(topType.getValue()), ids(topType.getValue())));
        byValue.stream().skip(1).forEach(h -> items.add(new BreakdownItem(h.description(), h.amount(), List.of(h.recordId()))));
        return new ComputedAnswer(plan, figures, items, used, scanned);
    }

    // ------------------------------------------------------------------- stocks

    /** Items: the holdings, largest first. */
    private static ComputedAnswer equities(QueryPlan plan, List<FinancialRecord> records, int scanned) {
        List<FinancialRecord> holdings = of(records, RecordKind.HOLDING);
        if (holdings.isEmpty()) {
            return empty(plan, scanned);
        }
        List<FinancialRecord> byValue = holdings.stream().sorted(Comparator.comparing(FinancialRecord::amount).reversed()).toList();
        BigDecimal total = QueryEngine.sum(holdings);
        BigDecimal topThree = QueryEngine.sum(byValue.stream().limit(3).toList());
        List<Figure> figures = List.of(
                new Figure("total", "Stock portfolio value", total, Unit.INR, ids(holdings)),
                new Figure("topShare", "Largest holding's share", ratio(byValue.get(0).amount(), total, 1), Unit.PERCENT, List.of(byValue.get(0).recordId())),
                new Figure("topThreeShare", "Top three holdings' share", ratio(topThree, total, 1), Unit.PERCENT, ids(byValue.stream().limit(3).toList())),
                new Figure("count", "Companies held", BigDecimal.valueOf(holdings.size()), Unit.COUNT, ids(holdings)),
                new Figure("concThreshold", "Concentration flag (illustrative)", STOCK_CONCENTRATION, Unit.PERCENT, List.of()));
        List<BreakdownItem> items = byValue.stream()
                .map(h -> new BreakdownItem(h.description(), h.amount(), List.of(h.recordId()))).toList();
        return new ComputedAnswer(plan, figures, items, holdings, scanned);
    }

    // ---------------------------------------------------------------------- EPF

    private static ComputedAnswer epf(QueryPlan plan, List<FinancialRecord> records, int scanned) {
        List<FinancialRecord> balances = of(records, RecordKind.BALANCE);
        if (balances.isEmpty()) {
            return empty(plan, scanned);
        }
        BigDecimal balance = QueryEngine.sum(balances);
        BigDecimal employer = balances.stream().map(r -> new BigDecimal(r.attributes().getOrDefault("employerBalance", "0")))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        List<FinancialRecord> contributions = latestMonths(of(records, Category.EPF_CONTRIBUTION), 3);

        List<FinancialRecord> used = new ArrayList<>(balances);
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("balance", "EPF balance", balance, Unit.INR, ids(balances)));
        if (!contributions.isEmpty()) {
            used.addAll(contributions);
            BigDecimal monthly = monthlyAverage(contributions);
            BigDecimal yearly = monthly.multiply(BigDecimal.valueOf(12)).setScale(2, RoundingMode.HALF_UP);
            figures.add(new Figure("monthlyContribution", "Added per month (you and your employer)", monthly, Unit.INR, ids(contributions)));
            figures.add(new Figure("yearlyContribution", "Added per year at this pace", yearly, Unit.INR, ids(contributions)));
            figures.add(new Figure("balanceInYear", "Balance in a year, before interest", balance.add(yearly), Unit.INR, ids(used)));
        }
        if (employer.signum() > 0 && balance.signum() > 0) {
            figures.add(new Figure("employerShare", "Employer's share of the balance", ratio(employer, balance, 1), Unit.PERCENT, ids(balances)));
        }
        List<FinancialRecord> interest = latestMonths(of(records, Category.EPF_INTEREST), 12);
        if (!interest.isEmpty()) {
            used.addAll(interest);
            figures.add(new Figure("interestYear", "Interest credited (recent months)", QueryEngine.sum(interest), Unit.INR, ids(interest)));
        }
        return new ComputedAnswer(plan, figures, List.of(new BreakdownItem("EPF balance", balance, ids(balances))), used, scanned);
    }

    // ------------------------------------------------------------------ overall

    /** Items: the asset types by value, largest first. */
    private static ComputedAnswer overall(QueryPlan plan, List<FinancialRecord> records, int scanned) {
        List<FinancialRecord> balances = of(records, RecordKind.BALANCE);
        if (balances.isEmpty()) {
            return empty(plan, scanned);
        }
        BigDecimal netWorth = QueryEngine.sum(balances);
        Map<FiType, List<FinancialRecord>> byType = balances.stream().collect(Collectors.groupingBy(FinancialRecord::fiType));
        List<Map.Entry<FiType, List<FinancialRecord>>> ranked = byType.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<FiType, List<FinancialRecord>> e) -> QueryEngine.sum(e.getValue())).reversed()).toList();

        List<FinancialRecord> used = new ArrayList<>(balances);
        List<Figure> figures = new ArrayList<>();
        figures.add(new Figure("netWorth", "Net worth", netWorth, Unit.INR, ids(balances)));

        List<FinancialRecord> spending = latestMonths(records.stream()
                .filter(r -> r.kind() == RecordKind.TRANSACTION && Category.SPENDING.contains(r.category())).toList(), 3);
        List<FinancialRecord> cash = byType.getOrDefault(FiType.DEPOSIT, List.of());
        if (!spending.isEmpty() && !cash.isEmpty()) {
            used.addAll(spending);
            BigDecimal monthlySpend = monthlyAverage(spending);
            figures.add(new Figure("monthlySpend", "Average monthly spending", monthlySpend, Unit.INR, ids(spending)));
            if (monthlySpend.signum() > 0) {
                figures.add(new Figure("runwayMonths", "Months your savings accounts would cover",
                        QueryEngine.sum(cash).divide(monthlySpend, 1, RoundingMode.HALF_UP), Unit.COUNT, ids(used)));
                figures.add(new Figure("emergencyRule", "Emergency cushion often aimed for (months)", EMERGENCY_MONTHS, Unit.COUNT, List.of()));
            }
        }
        for (Map.Entry<FiType, List<FinancialRecord>> e : ranked) {
            figures.add(new Figure(shareKey(e.getKey()), shareLabel(e.getKey()) + " share of net worth",
                    ratio(QueryEngine.sum(e.getValue()), netWorth, 1), Unit.PERCENT, ids(e.getValue())));
        }
        List<BreakdownItem> items = ranked.stream()
                .map(e -> new BreakdownItem(shareLabel(e.getKey()), QueryEngine.sum(e.getValue()), ids(e.getValue()))).toList();
        return new ComputedAnswer(plan, figures, items, used, scanned);
    }

    private static String shareKey(FiType type) {
        return switch (type) {
            case DEPOSIT -> "cashShare";
            case MUTUAL_FUNDS -> "fundsShare";
            case EQUITIES -> "stocksShare";
            case EPF -> "epfShare";
            case INSURANCE_POLICIES -> "insuranceShare";
        };
    }

    private static String shareLabel(FiType type) {
        return switch (type) {
            case DEPOSIT -> "Savings accounts";
            case MUTUAL_FUNDS -> "Mutual funds";
            case EQUITIES -> "Stocks";
            case EPF -> "EPF";
            case INSURANCE_POLICIES -> "Insurance";
        };
    }

    // ------------------------------------------------------------------ helpers

    private static ComputedAnswer empty(QueryPlan plan, int scanned) {
        return new ComputedAnswer(plan, List.of(), List.of(), List.of(), scanned);
    }

    private static List<FinancialRecord> of(List<FinancialRecord> records, RecordKind kind) {
        return records.stream().filter(r -> r.kind() == kind).toList();
    }

    private static List<FinancialRecord> of(List<FinancialRecord> records, Category category) {
        return records.stream().filter(r -> r.category() == category).toList();
    }

    /** The records in the three (or n) most recent calendar months that have any, counted from the newest record. */
    private static List<FinancialRecord> latestMonths(List<FinancialRecord> records, int n) {
        Set<YearMonth> months = new TreeSet<>(Comparator.reverseOrder());
        records.forEach(r -> months.add(YearMonth.from(r.date())));
        Set<YearMonth> keep = months.stream().limit(n).collect(Collectors.toSet());
        return records.stream().filter(r -> keep.contains(YearMonth.from(r.date()))).toList();
    }

    /** Total over the months present, divided by how many months that is. */
    private static BigDecimal monthlyAverage(List<FinancialRecord> records) {
        long months = Math.max(1, records.stream().map(r -> YearMonth.from(r.date())).distinct().count());
        return QueryEngine.sum(records).divide(BigDecimal.valueOf(months), 2, RoundingMode.HALF_UP);
    }

    /** The three most recent salary credits, newest first. */
    private static List<FinancialRecord> latestSalaries(List<FinancialRecord> records) {
        return records.stream().filter(r -> r.category() == Category.SALARY)
                .sorted(Comparator.comparing(FinancialRecord::date).reversed().thenComparing(FinancialRecord::recordId))
                .limit(3).toList();
    }

    private static BigDecimal yearlyIncome(List<FinancialRecord> salaries) {
        if (salaries.isEmpty()) {
            return BigDecimal.ZERO;
        }
        return QueryEngine.sum(salaries).divide(BigDecimal.valueOf(salaries.size()), 2, RoundingMode.HALF_UP)
                .multiply(BigDecimal.valueOf(12));
    }

    private static BigDecimal ratio(BigDecimal part, BigDecimal whole, int scale) {
        return whole.signum() == 0 ? BigDecimal.ZERO : part.multiply(HUNDRED).divide(whole, scale, RoundingMode.HALF_UP);
    }

    private static List<String> ids(List<FinancialRecord> records) {
        return records.stream().map(FinancialRecord::recordId).toList();
    }

    private static String title(String s) {
        String lower = s.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }
}
