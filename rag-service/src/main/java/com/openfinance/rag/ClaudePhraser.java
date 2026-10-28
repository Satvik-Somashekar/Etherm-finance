package com.openfinance.rag;

import com.anthropic.client.AnthropicClient;
import com.anthropic.errors.AnthropicException;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.openfinance.core.compute.ComputedAnswer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Asks Claude to phrase the answer using placeholders only. The model sees what each figure means
 * but never its value, so it cannot copy, round or alter one. Its sentence is rejected (and the
 * template used instead) if it contains any digit or an unknown placeholder.
 */
public class ClaudePhraser implements AnswerPhraser {

    private static final Logger log = LoggerFactory.getLogger(ClaudePhraser.class);
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-zA-Z0-9_]+}");

    static final String SYSTEM = """
            You phrase answers for a personal finance assistant. The numbers have already been computed \
            by the application. You receive the user's question and a list of placeholders, each with a \
            description of the figure it stands for. Write one or two short, plain sentences that answer \
            the question, referring to figures only by writing their placeholders exactly, for example \
            {total}. Never write a digit, a number in words, or a currency amount yourself; the \
            application substitutes the values. Do not add advice, caveats or facts that are not in the list. \
            Reply with the sentences only.""";

    /** For suggestions the model may be practical, but every figure still arrives through a placeholder. */
    static final String ADVICE_SYSTEM = """
            You write a short, practical spending suggestion for a personal finance assistant. The numbers have \
            already been computed by the application. You receive the user's question and a list of \
            placeholders, each with a description of the figure it stands for. Write three or four plain \
            sentences. Start with "Suggestion:". Name what stands out in the customer's numbers, give one or \
            two concrete, realistic next steps, and mention any rule of thumb or illustrative trim only as a \
            rough guide, referring to figures only by writing their placeholders exactly, for example {total}. Never \
            write a digit, a number in words, or a currency amount yourself; the application substitutes the \
            values. Say this is a suggestion, not a certainty. Do not give investment, tax or loan advice and \
            do not mention facts that are not in the list. Reply with the sentences only.""";

    static String systemFor(ComputedAnswer answer) {
        return answer.plan().intent() == com.openfinance.core.compute.Intent.ADVICE ? ADVICE_SYSTEM : SYSTEM;
    }

    private final AnthropicClient client;
    private final String model;
    private final TemplatePhraser fallback;

    public ClaudePhraser(AnthropicClient client, String model, TemplatePhraser fallback) {
        this.client = client;
        this.model = model;
        this.fallback = fallback;
    }

    @Override
    public Phrasing phrase(String question, ComputedAnswer answer) {
        if (answer.empty()) {
            return fallback.phrase(question, answer);
        }
        FactSheet facts = FactSheet.of(answer);
        try {
            String draft = complete(question, facts, answer);
            if (draft != null && isSafe(draft, facts, answer)) {
                return new Phrasing(facts.fill(draft), model);
            }
            log.warn("Model phrasing rejected, using template: {}", draft);
        } catch (AnthropicException e) {
            log.warn("Model phrasing failed, using template: {}", e.getMessage());
        }
        return fallback.phrase(question, answer);
    }

    /** What the model is told: the question, the period and what each placeholder means, never a value. */
    static String userMessage(String question, FactSheet facts, ComputedAnswer answer) {
        String placeholders = facts.descriptions().entrySet().stream()
                .map(e -> "{" + e.getKey() + "}: " + e.getValue())
                .collect(Collectors.joining("\n"));
        String labels = labels(facts);
        String period = answer.plan().period() == null ? "all shared data" : answer.plan().period().label();
        return "Question: " + question + "\nPeriod: " + period + "\nPlaceholders:\n" + placeholders
                + (labels.isEmpty() ? "" : "\nBreakdown item names (write these placeholders too):\n" + labels)
                + adviceHint(answer);
    }

    /** Tells the model what the area and the breakdown items are, in words only. */
    private static String adviceHint(ComputedAnswer answer) {
        if (answer.plan().intent() != com.openfinance.core.compute.Intent.ADVICE) {
            return "";
        }
        return switch (com.openfinance.core.compute.AdviceDomain.of(answer.plan())) {
            case SPENDING -> "\nArea: everyday spending. item1 is the category in focus (" + answer.plan().subject()
                    + "), item2 its largest payee, item3 the next largest flexible category, item4 the largest fixed cost; some may be absent.";
            case INSURANCE -> "\nArea: insurance. Items are the policies. Stay within cover, premiums and the figures given.";
            case MUTUAL_FUNDS -> "\nArea: mutual funds. item1 is the largest scheme, item2 the largest scheme type. Do not name funds to buy or sell.";
            case EQUITIES -> "\nArea: stocks. Items are the holdings. Talk about spread and concentration only, never a view on a company.";
            case EPF -> "\nArea: EPF and retirement savings.";
            case OVERALL -> "\nArea: overall finances. Items are the asset types, largest first.";
        };
    }

    private String complete(String question, FactSheet facts, ComputedAnswer answer) {
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(systemFor(answer))
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage(userMessage(question, facts, answer))
                .build();
        Message response = client.messages().create(params);
        if (response.stopReason().map(r -> r.equals(StopReason.REFUSAL)).orElse(false)) {
            return null;
        }
        return response.content().stream()
                .flatMap(block -> block.text().stream())
                .map(t -> t.text())
                .collect(Collectors.joining(" "))
                .trim();
    }

    private static String labels(FactSheet facts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5 && facts.label(i) != null; i++) {
            sb.append("{item").append(i).append("_label}\n");
        }
        return sb.toString().trim();
    }

    /** No digits or amounts in words of the model's own, no link or markup, and every placeholder it wrote is one we can fill. */
    static boolean isSafe(String draft, FactSheet facts, ComputedAnswer answer) {
        int limit = answer.plan().intent() == com.openfinance.core.compute.Intent.ADVICE ? 900 : 600;
        if (draft.isBlank() || draft.length() > limit) {
            return false;
        }
        String bare = facts.stripPlaceholders(draft);
        if (DIGIT.matcher(bare).find() || ChatService.NUMBER_WORDS.matcher(bare).find() || UntrustedText.hasLinkOrMarkup(draft)) {
            return false;
        }
        return !PLACEHOLDER.matcher(facts.fill(draft)).find();
    }
}
