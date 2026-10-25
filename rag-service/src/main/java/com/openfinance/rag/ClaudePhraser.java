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
            if (draft != null && isSafe(draft, facts)) {
                return new Phrasing(facts.fill(draft), model);
            }
            log.warn("Model phrasing rejected, using template: {}", draft);
        } catch (AnthropicException e) {
            log.warn("Model phrasing failed, using template: {}", e.getMessage());
        }
        return fallback.phrase(question, answer);
    }

    private String complete(String question, FactSheet facts, ComputedAnswer answer) {
        String placeholders = facts.descriptions().entrySet().stream()
                .map(e -> "{" + e.getKey() + "}: " + e.getValue())
                .collect(Collectors.joining("\n"));
        String labels = labels(facts);
        String period = answer.plan().period() == null ? "all shared data" : answer.plan().period().label();
        MessageCreateParams params = MessageCreateParams.builder()
                .model(model)
                .maxTokens(1024L)
                .system(SYSTEM)
                .outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build())
                .addUserMessage("Question: " + question + "\nPeriod: " + period + "\nPlaceholders:\n" + placeholders
                        + (labels.isEmpty() ? "" : "\nBreakdown item names (write these placeholders too):\n" + labels))
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

    /** No digits of the model's own, and every placeholder it wrote is one we can fill. */
    static boolean isSafe(String draft, FactSheet facts) {
        if (draft.isBlank() || draft.length() > 600) {
            return false;
        }
        if (DIGIT.matcher(facts.stripPlaceholders(draft)).find()) {
            return false;
        }
        return !PLACEHOLDER.matcher(facts.fill(draft)).find();
    }
}
