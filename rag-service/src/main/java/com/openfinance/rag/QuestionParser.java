package com.openfinance.rag;

import com.openfinance.core.compute.AdviceDomain;
import com.openfinance.core.compute.DateRange;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;

import java.time.LocalDate;
import java.time.Month;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a question into a {@link QueryPlan} with keyword and pattern rules. It is deterministic on
 * purpose: the plan decides which records are summed, so it is not left to a language model.
 * Relative periods ("last quarter") are resolved against {@code asOf}, normally today.
 */
public class QuestionParser {

    private static final Map<String, Category> CATEGORY_WORDS = new LinkedHashMap<>();

    static {
        for (String w : List.of("grocer", "supermarket", "vegetable")) CATEGORY_WORDS.put(w, Category.GROCERIES);
        for (String w : List.of("food", "dining", "restaurant", "eating out", "takeaway", "meal")) CATEGORY_WORDS.put(w, Category.FOOD);
        for (String w : List.of("fuel", "petrol", "diesel")) CATEGORY_WORDS.put(w, Category.FUEL);
        for (String w : List.of("shopping", "clothes", "online order")) CATEGORY_WORDS.put(w, Category.SHOPPING);
        for (String w : List.of("utilit", "electricity", "power bill", "bills")) CATEGORY_WORDS.put(w, Category.UTILITIES);
        for (String w : List.of("transport", "travel", "commut", "metro", "cab")) CATEGORY_WORDS.put(w, Category.TRANSPORT);
        CATEGORY_WORDS.put("rent", Category.RENT);
        for (String w : List.of("cash", "atm")) CATEGORY_WORDS.put(w, Category.CASH_WITHDRAWAL);
    }

    private static final Pattern TOP_N = Pattern.compile("\\btop\\s+(\\d{1,2})\\b|\\b(\\d{1,2})\\s+(?:biggest|largest|highest)");
    private static final Pattern LAST_N_MONTHS = Pattern.compile("\\b(?:last|past|previous)\\s+(\\d{1,2})\\s+months?\\b");
    private static final Pattern FY = Pattern.compile("\\bfy\\s*'?(\\d{2,4})\\s*[-/]\\s*(\\d{2,4})\\b");
    private static final Pattern YEAR = Pattern.compile("\\b(?:in|for|during)\\s+(20\\d{2})\\b");
    private static final String MONTHS = "(january|february|march|april|may|june|july|august|september|october|november|december|jan|feb|mar|apr|jun|jul|aug|sep|sept|oct|nov|dec)";
    private static final Pattern BETWEEN = Pattern.compile("\\b(?:between|from)\\s+" + MONTHS + "(?:\\s+(20\\d{2}))?\\s+(?:and|to|until|till)\\s+" + MONTHS + "(?:\\s+(20\\d{2}))?");
    private static final Pattern SINCE = Pattern.compile("\\bsince\\s+" + MONTHS + "(?:\\s+(20\\d{2}))?");
    private static final Pattern IN_MONTH = Pattern.compile("\\b(?:in|during|for|of)\\s+" + MONTHS + "(?:\\s+(20\\d{2}))?\\b");

    public QueryPlan parse(String question, LocalDate asOf) {
        String q = " " + question.toLowerCase(Locale.ROOT).replaceAll("[?!.,]", " ").replaceAll("\\s+", " ") + " ";
        QueryPlan plan = intent(q);
        if (plan.intent() == Intent.UNSUPPORTED) {
            return plan;
        }
        DateRange period = period(q, asOf);
        if (period != null && isFlow(plan.intent())) {
            plan = plan.withPeriod(period);
            if (plan.intent() != Intent.EXPORT_TRANSACTIONS && plan.intent() != Intent.EXPORT_REPORT && plan.intent() != Intent.ADVICE && has(q, "compar", " vs ", "versus", "increase", "decrease", "change", " grew", " drop", " than ", "went up", "went down")) {
                plan = plan.withCompare(true);
            }
        }
        return plan;
    }

    // ----------------------------------------------------------------- intent

    private QueryPlan intent(String q) {
        QueryPlan advice = advice(q);
        if (advice != null) {
            return advice;
        }
        // Tax planning and anything about hiding money are out of scope: answering with an income figure would look like help.
        if (has(q, " hide ", " hiding ", "evade", "evasion", "launder", "black money", "undeclared")
                || has(q, " tax") && has(q, "how ", " file ", "filing", "return", " save", "saving", "claim", "deduct", "regime", "slab", "avoid")) {
            return QueryPlan.of(Intent.UNSUPPORTED);
        }
        // Forecasts and recommendations on investments are out of scope: answering them with a balance would look like a recommendation.
        if (has(q, " should i ", " should we ", " good time ", " will ", " going to ", " predict", " forecast",
                " recommend", " advice", " advise", " best ")) {
            return QueryPlan.of(Intent.UNSUPPORTED);
        }
        QueryPlan export = export(q);
        if (export != null) {
            return export;
        }
        // Income set against spending is the savings picture, not an income-only question.
        if (has(q, "compar", " vs ", "versus", " against ", " and ") && has(q, "expense", "spend", "spent", "outgo")
                && has(q, "income", "salary", "earn")) {
            return QueryPlan.of(Intent.SAVINGS_RATE);
        }
        if (has(q, "savings rate", "saving rate", "how much did i save", "how much do i save", "how much have i saved",
                "how much i saved", "am i saving", "did i save", " i saved ", "left over", "leftover", "left after")
                && !has(q, "savings account")) {
            return QueryPlan.of(Intent.SAVINGS_RATE);
        }
        if (has(q, "merchant", "payee", "where do i spend", "where did i spend", "where does my money go",
                "where did my money go", "spend the most", "spent the most", "which shops", "most often")
                && !has(q, "category", "categories")) {
            Matcher m = TOP_N.matcher(q);
            int n = m.find() ? Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)) : 5;
            return QueryPlan.of(Intent.TOP_MERCHANTS).withTopN(Math.max(1, Math.min(n, 20)));
        }
        if (has(q, "month by month", "each month", "per month", "every month", "monthly spend", "monthly expense",
                "monthly average", "average monthly", "on average", "spending trend", "spend trend", "trend")
                && has(q, "spend", "spent", "expense", "spending", "outflow")) {
            return QueryPlan.of(Intent.SPEND_TREND);
        }
        if (has(q, "biggest", "largest", "highest", " top ") && has(q, "expense", "spend", "purchase", "transaction", "payment", "debit")) {
            Matcher m = TOP_N.matcher(q);
            int n = m.find() ? Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(2)) : 5;
            return QueryPlan.of(Intent.TOP_EXPENSES).withTopN(Math.max(1, Math.min(n, 20)));
        }
        if (has(q, "premium") || (has(q, "insurance", "policy", "policies") && has(q, "cost", " paid ", " pay ", "spend", "spent"))) {
            return QueryPlan.of(Intent.PREMIUMS_PAID);
        }
        if (has(q, "sum assured", "cover", "insured for", "insurance worth", "life insurance", "health insurance") && !has(q, "spend", "spent", "paid")) {
            QueryPlan p = QueryPlan.of(Intent.INSURANCE_COVER);
            if (has(q, "life", "term")) {
                return p.withSubject("TERM");
            }
            return has(q, "health", "medical") ? p.withSubject("HEALTH") : p;
        }
        if (has(q, " epf", " pf ", "provident")) {
            return has(q, "contribut", "credited", "deposited", "added", "put in")
                    ? QueryPlan.of(Intent.EPF_CONTRIBUTIONS)
                    : QueryPlan.of(Intent.BALANCE).withFiTypes(Set.of(FiType.EPF));
        }
        boolean mf = has(q, "mutual fund", " mf ", " mfs ", " sip", " fund ", " funds ", " folio");
        boolean equity = has(q, "stock", "share", "equit", "demat", "portfolio of companies");
        if (mf && has(q, "invest", " sip", "put into", "put in", "bought", "purchase")) {
            return QueryPlan.of(Intent.MF_INVESTED);
        }
        if ((mf || equity) && has(q, "holding", "which ", "what funds", "breakdown", "each ", "largest", "biggest", " top ")) {
            return QueryPlan.of(Intent.HOLDINGS).withFiTypes(mf && !equity ? Set.of(FiType.MUTUAL_FUNDS)
                    : equity && !mf ? Set.of(FiType.EQUITIES) : Set.of(FiType.MUTUAL_FUNDS, FiType.EQUITIES));
        }
        if (mf || equity) {
            Set<FiType> types = EnumSet.noneOf(FiType.class);
            if (mf) types.add(FiType.MUTUAL_FUNDS);
            if (equity) types.add(FiType.EQUITIES);
            return QueryPlan.of(Intent.BALANCE).withFiTypes(types);
        }
        if (has(q, "net worth", "networth", "total assets", "total wealth", "worth overall", "how much am i worth", "all my accounts")) {
            return QueryPlan.of(Intent.BALANCE);
        }
        if (has(q, "salary", "income", "earn", "paid me", "received", "credited", "came in", "come in", "inflow", "incoming")) {
            return has(q, "interest") ? QueryPlan.of(Intent.INCOME).withCategories(Set.of(Category.INTEREST))
                    : QueryPlan.of(Intent.INCOME);
        }
        if (has(q, "interest") && has(q, "earn", "got", "receive", "how much")) {
            return QueryPlan.of(Intent.INCOME).withCategories(Set.of(Category.INTEREST));
        }
        if (has(q, "balance", "in my bank", "savings account", "in the bank")) {
            return QueryPlan.of(Intent.BALANCE).withFiTypes(Set.of(FiType.DEPOSIT));
        }
        Set<Category> cats = EnumSet.noneOf(Category.class);
        CATEGORY_WORDS.forEach((word, cat) -> {
            if (q.contains(word)) {
                cats.add(cat);
            }
        });
        // A spending category on its own ("rent last month") is enough to mean spending.
        if (!cats.isEmpty() || has(q, "spend", "spent", "expense", "expenditure", " paid ", " pay ", "cost me", "outflow", "went out", "gone out", "outgoing")) {
            return cats.isEmpty() ? QueryPlan.of(Intent.SPEND_TOTAL)
                    : QueryPlan.of(Intent.SPEND_CATEGORY).withCategories(cats);
        }
        return QueryPlan.of(Intent.UNSUPPORTED);
    }

    /** Words that turn a question into a request for a suggestion or a plan. */
    private static final String[] SAVE_WORDS = {"reduce", "cut down", "cut back", "cut my", "cut it", "cut costs", " lower ",
            "spend less", "save more", "saving more", "trim", " tips", "suggest", "budget", "how can i save", "how do i save",
            "how to save", "curb", "control my spend", "overspend", "too much", "too high", "improve my sav"};
    private static final String[] PLAN_WORDS = {" plan ", "plan my", "plan for", "review my", "health check", "financial health",
            "how am i doing", "how is my", "how are my", "what should i do", "any advice", "help me plan", "help me understand",
            "understand my", "explain my", " enough", "adequate", "diversif", "concentrat", "on track", "balanced", "well covered",
            "am i covered", "am i insured", "improve", "overall"};
    /** Questions that need a prediction, a product pick or a tax or credit opinion are still declined. */
    private static final String[] NOT_ADVICE = {" buy ", " sell ", "which stock", "which fund", "which scheme", "which policy",
            " best ", " tax", "loan", "crypto", "fixed deposit", " fd ", "predict", "forecast", " will ", "going to",
            "good time", "return", "should i invest", "should i put", "trend"};

    /**
     * A suggestion or planning question. The subject names what it is about (an {@link AdviceDomain}), or is
     * GENERIC_SPEND / GENERIC_PLAN when the question names no area, so the Knowledge Base section the customer
     * is in can decide.
     */
    private QueryPlan advice(String q) {
        boolean saves = has(q, SAVE_WORDS);
        boolean plans = has(q, PLAN_WORDS);
        if ((!saves && !plans) || has(q, NOT_ADVICE)) {
            return null;
        }
        Set<Category> cats = EnumSet.noneOf(Category.class);
        CATEGORY_WORDS.forEach((word, cat) -> {
            if (q.contains(word)) {
                cats.add(cat);
            }
        });
        QueryPlan plan = QueryPlan.of(Intent.ADVICE);
        if (has(q, "insurance", "insured", "policy", "policies", "premium", " cover", "covered")) {
            return plan.withSubject(AdviceDomain.INSURANCE.name());
        }
        if (has(q, "mutual fund", " mf ", " mfs ", " sip", " fund", " folio", "scheme")) {
            return plan.withSubject(AdviceDomain.MUTUAL_FUNDS.name());
        }
        if (has(q, "stock", " share", "equit", "demat")) {
            return plan.withSubject(AdviceDomain.EQUITIES.name());
        }
        if (has(q, " epf", " pf ", "provident", "retire", "pension")) {
            return plan.withSubject(AdviceDomain.EPF.name());
        }
        if (!cats.isEmpty() || has(q, "spend", "expense", "saving", "save", "money", "income", "salary")) {
            return plan.withCategories(cats);
        }
        return plan.withSubject(plans ? "GENERIC_PLAN" : "GENERIC_SPEND");
    }

    /** A request for a file: "export my transactions", "download a report", "give me a csv for March". */
    private QueryPlan export(String q) {
        boolean asks = has(q, "export", "download", " csv", "spreadsheet", "excel", "as a file")
                || (has(q, "report", "statement") && has(q, "generate", "create", "make", "give me", "prepare", "produce", "need", "want", "send"));
        if (!asks) {
            return null;
        }
        if (has(q, "report", "summary")) {
            return QueryPlan.of(Intent.EXPORT_REPORT);
        }
        Set<Category> cats = EnumSet.noneOf(Category.class);
        CATEGORY_WORDS.forEach((word, cat) -> {
            if (q.contains(word)) {
                cats.add(cat);
            }
        });
        if (has(q, "salary")) {
            cats.add(Category.SALARY);
        }
        return QueryPlan.of(Intent.EXPORT_TRANSACTIONS).withCategories(cats);
    }

    private static boolean isFlow(Intent i) {
        return switch (i) {
            case SPEND_TOTAL, SPEND_CATEGORY, TOP_EXPENSES, INCOME, PREMIUMS_PAID, MF_INVESTED, EPF_CONTRIBUTIONS,
                 ADVICE, SAVINGS_RATE, TOP_MERCHANTS, SPEND_TREND, EXPORT_TRANSACTIONS, EXPORT_REPORT -> true;
            default -> false;
        };
    }

    // ----------------------------------------------------------------- period

    DateRange period(String q, LocalDate asOf) {
        YearMonth current = YearMonth.from(asOf);
        Matcher m;
        if ((m = BETWEEN.matcher(q)).find()) {
            YearMonth a = month(m.group(1), m.group(2), asOf);
            YearMonth b = month(m.group(3), m.group(4), asOf);
            if (b.isBefore(a)) {
                a = a.minusYears(1);
            }
            return new DateRange(a.atDay(1), b.atEndOfMonth(), m.group().trim());
        }
        if ((m = LAST_N_MONTHS.matcher(q)).find()) {
            int n = Integer.parseInt(m.group(1));
            return new DateRange(current.minusMonths(n).atDay(1), current.minusMonths(1).atEndOfMonth(), "the last " + n + " months");
        }
        if ((m = FY.matcher(q)).find()) {
            int start = fullYear(m.group(1));
            return new DateRange(LocalDate.of(start, 4, 1), LocalDate.of(start + 1, 3, 31), "FY " + start + "-" + String.valueOf(start + 1).substring(2));
        }
        int fyStart = asOf.getMonthValue() >= 4 ? asOf.getYear() : asOf.getYear() - 1;
        if (has(q, "this financial year", "this fy", "current financial year")) {
            return new DateRange(LocalDate.of(fyStart, 4, 1), asOf, "this financial year");
        }
        if (has(q, "last financial year", "previous financial year", "last fy")) {
            return new DateRange(LocalDate.of(fyStart - 1, 4, 1), LocalDate.of(fyStart, 3, 31), "last financial year");
        }
        if (has(q, "last month", "previous month")) {
            YearMonth p = current.minusMonths(1);
            return new DateRange(p.atDay(1), p.atEndOfMonth(), "last month");
        }
        if (has(q, "this month")) {
            return new DateRange(current.atDay(1), asOf, "this month");
        }
        int quarterStartMonth = ((asOf.getMonthValue() - 1) / 3) * 3 + 1;
        LocalDate quarterStart = LocalDate.of(asOf.getYear(), quarterStartMonth, 1);
        if (has(q, "last quarter", "previous quarter", "past quarter")) {
            return new DateRange(quarterStart.minusMonths(3), quarterStart.minusDays(1), "last quarter");
        }
        if (has(q, "this quarter")) {
            return new DateRange(quarterStart, asOf, "this quarter");
        }
        if (has(q, "last year", "past year", "last 12 months", "past 12 months", "previous year")) {
            return new DateRange(current.minusMonths(12).atDay(1), current.minusMonths(1).atEndOfMonth(), "the last 12 months");
        }
        if (has(q, "this year", "year to date", " ytd ")) {
            return new DateRange(LocalDate.of(asOf.getYear(), 1, 1), asOf, "this year");
        }
        if ((m = SINCE.matcher(q)).find()) {
            YearMonth a = month(m.group(1), m.group(2), asOf);
            return new DateRange(a.atDay(1), asOf, "since " + name(a));
        }
        if ((m = IN_MONTH.matcher(q)).find()) {
            YearMonth a = month(m.group(1), m.group(2), asOf);
            return new DateRange(a.atDay(1), a.atEndOfMonth(), "in " + name(a));
        }
        if ((m = YEAR.matcher(q)).find()) {
            int y = Integer.parseInt(m.group(1));
            return new DateRange(LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31), "in " + y);
        }
        return null;
    }

    /** A named month with no year means its most recent occurrence that has started by {@code asOf}. */
    private static YearMonth month(String word, String year, LocalDate asOf) {
        Month month = parseMonth(word);
        if (year != null) {
            return YearMonth.of(Integer.parseInt(year), month);
        }
        YearMonth candidate = YearMonth.of(asOf.getYear(), month);
        return candidate.isAfter(YearMonth.from(asOf)) ? candidate.minusYears(1) : candidate;
    }

    private static Month parseMonth(String word) {
        for (Month m : Month.values()) {
            if (m.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT).startsWith(word.substring(0, 3))) {
                return m;
            }
        }
        throw new IllegalArgumentException("not a month: " + word);
    }

    private static int fullYear(String y) {
        int v = Integer.parseInt(y);
        return v < 100 ? 2000 + v : v;
    }

    private static String name(YearMonth ym) {
        return ym.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + ym.getYear();
    }

    private static boolean has(String q, String... words) {
        for (String w : words) {
            if (q.contains(w)) {
                return true;
            }
        }
        return false;
    }
}
