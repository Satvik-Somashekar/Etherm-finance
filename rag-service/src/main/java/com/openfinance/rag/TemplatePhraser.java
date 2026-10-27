package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.Figure;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.model.Category;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Deterministic phrasing. Writes sentences with placeholders and lets {@link FactSheet} insert
 * the numbers, the same path the model phraser uses.
 */
@Component
public class TemplatePhraser implements AnswerPhraser {

    static final String UNSUPPORTED = "I can answer questions about your spending (overall or by category), "
            + "largest expenses, where your money goes, month-by-month spending, how much you save, salary, "
            + "insurance premiums and cover, mutual fund investments, EPF contributions, "
            + "and the current value of your accounts. I can also prepare your transactions or a monthly report "
            + "as a CSV file to download. Try \"How much did I spend on groceries last quarter?\"";

    @Override
    public Phrasing phrase(String question, ComputedAnswer answer) {
        return new Phrasing(FactSheet.of(answer).fill(template(answer)), "template");
    }

    /** The sentence with placeholders; package-visible so tests can check no digits are hard-coded. */
    String template(ComputedAnswer answer) {
        QueryPlan plan = answer.plan();
        FactSheet facts = FactSheet.of(answer);
        String period = plan.period() == null ? "over the period you have shared" : plan.period().label();
        if (answer.empty()) {
            return switch (plan.intent()) {
                case UNSUPPORTED -> UNSUPPORTED;
                default -> "I found no matching records " + (plan.period() == null ? "" : period + " ") + "in the accounts you have shared.";
            };
        }
        String top = facts.label(1) == null ? "" : facts.label(1);
        String sentence = switch (plan.intent()) {
            case SPEND_TOTAL -> "You spent {total} " + period + " across " + count(answer, "transaction") + ". The largest category was "
                    + top + " at {item1_value}.";
            case SPEND_CATEGORY -> "You spent {total} on " + categories(plan) + " " + period + " across " + count(answer, "transaction")
                    + (answer.breakdown().size() > 1 ? ", most of it at " + top + " ({item1_value})." : ".");
            case TOP_EXPENSES -> "Your largest spend " + period + " was {item1_value} (" + top + ")"
                    + (answer.breakdown().size() > 1 ? ", followed by {item2_value} (" + facts.label(2) + ")." : ".");
            case INCOME -> "You received {total} " + (plan.categories().isEmpty() ? "in salary" : "in interest") + " "
                    + period + " across " + count(answer, "credit") + ".";
            case PREMIUMS_PAID -> "You paid {total} in insurance premiums " + period + " across " + count(answer, "payment") + ". "
                    + top + " accounted for {item1_value}.";
            case MF_INVESTED -> "You invested {total} in mutual funds " + period + " across " + count(answer, "purchase") + ", the most in "
                    + top + " ({item1_value}).";
            case EPF_CONTRIBUTIONS -> "{total} was credited to your EPF " + period + ": {employeeShare} from you and "
                    + "{employerShare} from your employer.";
            case BALANCE -> (answer.figure("total").label().equals("Net worth")
                    ? "Your net worth across " + count(answer, "account") + " is {total}." : "The current value is {total} across " + count(answer, "account") + ".")
                    + " The largest is " + top + " at {item1_value}.";
            case HOLDINGS -> "Your " + count(answer, "holding") + " " + (answer.figure("count").value().intValue() == 1 ? "is" : "are") + " worth {total}. The largest is " + top + " at {item1_value}.";
            case INSURANCE_COVER -> "Your policies carry a total sum assured of {total}"
                    + (answer.breakdown().size() > 1 ? ", the largest being " + top + " ({item1_value})." : ".");
            case SAVINGS_RATE -> savings(answer, period);
            case TOP_MERCHANTS -> "Most of your spending " + period + " went to " + top + " ({item1_value})"
                    + (answer.breakdown().size() > 1 ? ", then " + facts.label(2) + " ({item2_value})" : "")
                    + (answer.breakdown().size() > 2 ? " and " + facts.label(3) + " ({item3_value})" : "")
                    + ". In total you spent {total} across {merchants} payees.";
            case EXPORT_TRANSACTIONS -> "I prepared a CSV of {count} " + (plan.categories().isEmpty() ? "" : categories(plan) + " ")
                    + "transactions" + (plan.period() == null ? "" : " (" + period + ")")
                    + ". Use the Download button to save it.";
            case EXPORT_REPORT -> "I prepared a monthly report covering {months} months" + (plan.period() == null ? "" : " (" + period + ")")
                    + ", built from {count} transactions. Use the Download button to save it.";
            case SPEND_TREND -> "You spent {total} " + period + ", an average of {monthlyAverage} a month. "
                    + top + " was the highest month at {item1_value}.";
            case ADVICE -> advice(answer, facts, period);
            case UNSUPPORTED -> UNSUPPORTED;
        };
        return sentence + comparison(answer);
    }

    /** A practical suggestion: the category, a lever specific to it, and the saving a 10% trim would free up. */
    private static String advice(ComputedAnswer answer, FactSheet facts, String period) {
        QueryPlan plan = answer.plan();
        Category category = plan.categories().iterator().next();
        boolean fixed = "FIXED".equals(plan.subject());
        StringBuilder s = new StringBuilder("Suggestion: ");
        if (fixed) {
            s.append("{item1_label} is a fixed cost for you, about {focusMonthly} a month, so it will not shrink overnight. ")
                    .append(TIPS.get(category)).append(" Even a {trimPct} saving there frees {trim10Monthly} a month. ");
            if (facts.label(3) != null) {
                s.append("The quicker win is {item3_label}, where a {trimPct} trim is easier to start. ");
            }
        } else {
            s.append("{item1_label} is where you can most easily save: about {focusMonthly} a month, {focusShare} of everything you spend. ")
                    .append(TIPS.get(category)).append(" Trimming it by {trimPct} would free about {trim10Monthly} a month, {trim10Yearly} over a year");
            if (facts.has("newSavingsRate")) {
                s.append(", moving your savings rate from {savingsRate} to {newSavingsRate}");
            }
            s.append(". ");
            if (facts.label(2) != null) {
                s.append("Your largest payee here is {item2_label} ({item2_value} " + period + "), a good place to start.");
            }
        }
        return s.toString().trim();
    }

    private static final java.util.Map<Category, String> TIPS = java.util.Map.of(
            Category.GROCERIES, "Planning one weekly shop and buying staples in bulk usually trims this without changing what you eat.",
            Category.FOOD, "A weekly cap on eating out, and ordering in less often, is the quickest lever.",
            Category.SHOPPING, "Waiting a day or two before non-essential purchases cuts the impulse buys.",
            Category.FUEL, "Combining errands into fewer trips cuts fuel without giving anything up.",
            Category.TRANSPORT, "Using public transport or shared rides for routine trips is the practical lever.",
            Category.CASH_WITHDRAWAL, "Cash disappears untracked, so paying digitally makes the spending visible.",
            Category.OTHER_DEBIT, "Scan these transactions for recurring charges you no longer use.",
            Category.RENT, "The lever is the next renewal, where negotiating or comparing nearby options can help.",
            Category.UTILITIES, "Checking your plans and tariffs for cheaper options is the practical lever.",
            Category.PREMIUM_DEBIT, "Compare renewal quotes and check the cover still fits before renewing.");

    private static String savings(ComputedAnswer answer, String period) {
        Figure saved = answer.figure("saved");
        if (saved != null && FactSheet.isNegative(saved)) {
            return "You spent more than your salary " + period + ": {spending} spent against {income} received.";
        }
        return "You received {income} in salary " + period + " and spent {spending}, leaving {saved}"
                + (answer.figure("savingsRate") != null ? ", a savings rate of {savingsRate}." : ".");
    }

    /** "{count} payment" or "{count} payments", depending on the computed count. */
    private static String count(ComputedAnswer answer, String noun) {
        Figure f = answer.figure("count");
        return "{count} " + noun + (f != null && f.value().compareTo(BigDecimal.ONE) == 0 ? "" : "s");
    }

    private static String comparison(ComputedAnswer answer) {
        Figure change = answer.figure("change");
        if (change == null) {
            return "";
        }
        if (change.value().signum() == 0) {
            return " That is the same as the period before ({previousTotal}).";
        }
        String direction = FactSheet.isNegative(change) ? "down" : "up";
        return " That is " + direction + " from {previousTotal} the period before"
                + (answer.figure("changePct") != null ? " ({changePct})." : ".");
    }

    private static String categories(QueryPlan plan) {
        return plan.categories().stream().map(c -> c.name().replace('_', ' ').toLowerCase())
                .sorted().reduce((a, b) -> a + " and " + b).orElse("that");
    }
}
