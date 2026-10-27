package com.openfinance.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.compute.AdviceDomain;
import com.openfinance.core.compute.DateRange;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The lookups the chat model may call. Each one becomes a {@link QueryPlan} that the Java compute layer runs;
 * the model chooses what to look up and for when, and never computes anything itself.
 */
final class ChatTools {

    private static final List<String> PERIODS = List.of("last_month", "this_month", "last_quarter", "this_quarter",
            "last_3_months", "last_6_months", "last_12_months", "this_financial_year", "last_financial_year", "this_year");
    private static final List<String> SPENDING_CATEGORIES = Category.SPENDING.stream().map(Enum::name).sorted().toList();

    /** One tool: its declaration for the model and the account types it reads. */
    record Tool(String name, String description, Map<String, Object> parameters, Set<FiType> reads) {
    }

    private static final List<Tool> TOOLS = new ArrayList<>();

    static {
        Map<String, Object> period = Map.of("type", "string", "enum", PERIODS,
                "description", "A named period relative to today. Omit to use everything the customer has shared.");
        Map<String, Object> topN = Map.of("type", "integer", "description", "How many to list, 1 to 20.");
        add("get_spending", "Total spending (debits) in a period with a breakdown by category, optionally limited to some categories "
                + "and optionally compared with the period before.", Map.of(
                "period", period,
                "categories", Map.of("type", "array", "items", Map.of("type", "string", "enum", SPENDING_CATEGORIES)),
                "compare_previous", Map.of("type", "boolean")), Set.of(FiType.DEPOSIT));
        add("get_income", "Salary received in a period, by month.", Map.of("period", period), Set.of(FiType.DEPOSIT));
        add("get_savings", "Income (salary) and spending side by side for a period, with what was left over and the savings rate. "
                + "Use this to compare expenses with income.", Map.of("period", period), Set.of(FiType.DEPOSIT));
        add("get_monthly_trend", "Spending month by month with the monthly average.", Map.of("period", period), Set.of(FiType.DEPOSIT));
        add("get_top_merchants", "Where the money went, grouped by payee, largest first.",
                Map.of("period", period, "top_n", topN), Set.of(FiType.DEPOSIT));
        add("get_biggest_expenses", "The largest individual expenses in a period.",
                Map.of("period", period, "top_n", topN), Set.of(FiType.DEPOSIT));
        add("get_balances", "Current value of accounts. With no areas given it is the net worth across everything; areas limit it.",
                Map.of("areas", Map.of("type", "array", "items", Map.of("type", "string",
                        "enum", List.of("savings_accounts", "mutual_funds", "stocks", "epf")))),
                EnumSet.of(FiType.DEPOSIT, FiType.MUTUAL_FUNDS, FiType.EQUITIES, FiType.EPF));
        add("get_holdings", "Current value of each mutual fund or stock holding.",
                Map.of("areas", Map.of("type", "array", "items", Map.of("type", "string", "enum", List.of("mutual_funds", "stocks")))),
                EnumSet.of(FiType.MUTUAL_FUNDS, FiType.EQUITIES));
        add("get_insurance_cover", "Sum assured on insurance policies, by policy.",
                Map.of("policy_type", Map.of("type", "string", "enum", List.of("TERM", "HEALTH"))), Set.of(FiType.INSURANCE_POLICIES));
        add("get_premiums_paid", "Insurance premiums paid in a period, by policy.", Map.of("period", period), Set.of(FiType.INSURANCE_POLICIES));
        add("get_mutual_fund_investing", "Money put into mutual funds in a period, by scheme.", Map.of("period", period), Set.of(FiType.MUTUAL_FUNDS));
        add("get_epf_contributions", "EPF contributions credited in a period, split into employee and employer shares.",
                Map.of("period", period), Set.of(FiType.EPF));
        add("get_review", "A computed review of one area with rule-of-thumb comparisons and what a small change would free up: "
                + "spending (the category in focus and what a 10 percent trim saves), insurance (cover against salary), mutual_funds "
                + "and stocks (concentration), epf (growth), overall (net worth mix and emergency cushion). Use it for questions "
                + "about how someone is doing, what to improve, or planning.",
                Map.of("area", Map.of("type", "string", "enum", List.of("spending", "insurance", "mutual_funds", "stocks", "epf", "overall")),
                        "category", Map.of("type", "string", "enum", SPENDING_CATEGORIES, "description", "For area spending: a category to focus on."),
                        "period", period), Set.of());
    }

    private ChatTools() {
    }

    private static void add(String name, String description, Map<String, Object> properties, Set<FiType> reads) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        TOOLS.add(new Tool(name, description, parameters, reads));
    }

    /** The declarations to offer: tools reading only outside the customer's selected section are left out. */
    static List<Map<String, Object>> declarations(Set<FiType> scope) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Tool t : TOOLS) {
            if (scope.isEmpty() || t.reads().isEmpty() || t.reads().stream().anyMatch(scope::contains)) {
                out.add(Map.of("name", t.name(), "description", t.description(), "parameters", t.parameters()));
            }
        }
        return out;
    }

    /** The plan a tool call stands for, or null for an unknown tool. Bad arguments raise IllegalArgumentException. */
    static QueryPlan plan(String name, JsonNode args, QuestionParser parser, LocalDate today) {
        DateRange period = period(args.path("period").asText(""), parser, today);
        QueryPlan plan = switch (name) {
            case "get_spending" -> {
                Set<Category> cats = categories(args.path("categories"));
                QueryPlan p = cats.isEmpty() ? QueryPlan.of(Intent.SPEND_TOTAL) : QueryPlan.of(Intent.SPEND_CATEGORY).withCategories(cats);
                yield p.withCompare(args.path("compare_previous").asBoolean(false) && period != null);
            }
            case "get_income" -> QueryPlan.of(Intent.INCOME);
            case "get_savings" -> QueryPlan.of(Intent.SAVINGS_RATE);
            case "get_monthly_trend" -> QueryPlan.of(Intent.SPEND_TREND);
            case "get_top_merchants" -> QueryPlan.of(Intent.TOP_MERCHANTS).withTopN(topN(args));
            case "get_biggest_expenses" -> QueryPlan.of(Intent.TOP_EXPENSES).withTopN(topN(args));
            case "get_balances" -> QueryPlan.of(Intent.BALANCE).withFiTypes(areas(args.path("areas")));
            case "get_holdings" -> QueryPlan.of(Intent.HOLDINGS).withFiTypes(areas(args.path("areas")));
            case "get_insurance_cover" -> {
                QueryPlan p = QueryPlan.of(Intent.INSURANCE_COVER);
                String type = args.path("policy_type").asText("");
                yield type.isBlank() ? p : p.withSubject(type.toUpperCase(Locale.ROOT));
            }
            case "get_premiums_paid" -> QueryPlan.of(Intent.PREMIUMS_PAID);
            case "get_mutual_fund_investing" -> QueryPlan.of(Intent.MF_INVESTED);
            case "get_epf_contributions" -> QueryPlan.of(Intent.EPF_CONTRIBUTIONS);
            case "get_review" -> review(args);
            default -> null;
        };
        if (plan == null) {
            return null;
        }
        return period == null ? plan : plan.withPeriod(period);
    }

    private static QueryPlan review(JsonNode args) {
        QueryPlan plan = QueryPlan.of(Intent.ADVICE);
        return switch (args.path("area").asText("spending")) {
            case "insurance" -> plan.withSubject(AdviceDomain.INSURANCE.name());
            case "mutual_funds" -> plan.withSubject(AdviceDomain.MUTUAL_FUNDS.name());
            case "stocks" -> plan.withSubject(AdviceDomain.EQUITIES.name());
            case "epf" -> plan.withSubject(AdviceDomain.EPF.name());
            case "overall" -> plan.withSubject(AdviceDomain.OVERALL.name());
            default -> plan.withCategories(categories(args.path("category")));
        };
    }

    private static DateRange period(String name, QuestionParser parser, LocalDate today) {
        if (name.isBlank()) {
            return null;
        }
        if (!PERIODS.contains(name)) {
            throw new IllegalArgumentException("unknown period: " + name);
        }
        String phrase = switch (name) {
            case "last_3_months" -> "last 3 months";
            case "last_6_months" -> "last 6 months";
            case "last_12_months" -> "last 12 months";
            default -> name.replace('_', ' ');
        };
        return parser.period(" " + phrase + " ", today);
    }

    private static Set<Category> categories(JsonNode node) {
        Set<Category> out = EnumSet.noneOf(Category.class);
        if (node.isTextual() && !node.asText().isBlank()) {
            out.add(Category.valueOf(node.asText().toUpperCase(Locale.ROOT)));
        }
        if (node.isArray()) {
            node.forEach(n -> out.add(Category.valueOf(n.asText().toUpperCase(Locale.ROOT))));
        }
        return out;
    }

    private static Set<FiType> areas(JsonNode node) {
        Set<FiType> out = EnumSet.noneOf(FiType.class);
        if (node.isArray()) {
            node.forEach(n -> out.add(switch (n.asText()) {
                case "savings_accounts" -> FiType.DEPOSIT;
                case "mutual_funds" -> FiType.MUTUAL_FUNDS;
                case "stocks" -> FiType.EQUITIES;
                case "epf" -> FiType.EPF;
                default -> throw new IllegalArgumentException("unknown area: " + n.asText());
            }));
        }
        return out;
    }

    private static int topN(JsonNode args) {
        return Math.max(1, Math.min(args.path("top_n").asInt(5), 20));
    }
}
