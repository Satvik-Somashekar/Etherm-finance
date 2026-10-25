package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.Figure;
import com.openfinance.core.compute.QueryPlan;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Deterministic phrasing. Writes sentences with placeholders and lets {@link FactSheet} insert
 * the numbers, the same path the model phraser uses.
 */
@Component
public class TemplatePhraser implements AnswerPhraser {

    static final String UNSUPPORTED = "I can answer questions about your spending (overall or by category), "
            + "largest expenses, salary, insurance premiums and cover, mutual fund investments, EPF contributions, "
            + "and the current value of your accounts. Try \"How much did I spend on groceries last quarter?\"";

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
            case UNSUPPORTED -> UNSUPPORTED;
        };
        return sentence + comparison(answer);
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
