package com.openfinance.core.compute;

import com.openfinance.core.model.FinancialRecord;

import java.util.List;

/**
 * Everything the compute step produced for one question. {@link #citations()} holds exactly the
 * records that contributed to a figure, nothing that was merely retrieved.
 */
public record ComputedAnswer(
        QueryPlan plan,
        List<Figure> figures,
        List<BreakdownItem> breakdown,
        List<FinancialRecord> citations,
        int recordsScanned) {

    public ComputedAnswer {
        figures = List.copyOf(figures);
        breakdown = List.copyOf(breakdown);
        citations = List.copyOf(citations);
    }

    public boolean empty() {
        return citations.isEmpty();
    }

    public Figure figure(String key) {
        return figures.stream().filter(f -> f.key().equals(key)).findFirst().orElse(null);
    }
}
