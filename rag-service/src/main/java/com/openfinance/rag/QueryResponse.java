package com.openfinance.rag;

import com.openfinance.core.compute.BreakdownItem;
import com.openfinance.core.compute.Figure;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.model.FiType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * @param answer   the sentence; display text only
 * @param figures  the authoritative numbers, computed in Java. A UI shows figures from here, never
 *                 parsed out of {@code answer}
 */
public record QueryResponse(
        String question,
        String answer,
        String phrasedBy,
        Intent intent,
        Period period,
        List<Figure> figures,
        List<BreakdownItem> breakdown,
        List<Citation> citations,
        Grounding grounding,
        Export export) {

    /**
     * Present when the question asked for a file. The client downloads it from the customer's export
     * endpoint with these parameters; the file is built from consented records, like the answer.
     *
     * @param kind       {@code transactions} or {@code report}
     * @param categories transaction categories to keep; empty means all
     */
    public record Export(String kind, LocalDate from, LocalDate to, List<String> categories) {
    }

    public record Period(LocalDate from, LocalDate to, String label) {
    }

    public record Citation(String recordId, String accountId, String fipId, FiType fiType, LocalDate date,
                           BigDecimal amount, String description, String counterparty) {
    }

    /**
     * @param recordsVisible records the consents allowed
     * @param recordsMatched records retrieval returned for this question
     * @param recordsCited   records that contributed to a figure
     */
    public record Grounding(List<String> consentIds, int recordsVisible, int recordsMatched, int recordsCited,
                            LocalDate asOf) {
    }
}
