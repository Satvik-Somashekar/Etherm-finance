package com.openfinance.core.oracle;

import java.util.List;

/**
 * What a tool returns to the model. Text from records (narrations, names) is untrusted data and is
 * returned only inside {@code data}; the model's instructions never come from here.
 *
 * @param toolCallId the id of the call this answers
 * @param consentIds the consents the call was allowed under
 */
public record ToolResult(
        String toolCallId,
        ToolName tool,
        List<String> consentIds,
        List<ResultObject> results,
        List<Recommendation> recommendations,
        List<Goal.Progress> goals,
        List<String> notes) {

    public ToolResult {
        consentIds = consentIds == null ? List.of() : List.copyOf(consentIds);
        results = results == null ? List.of() : List.copyOf(results);
        recommendations = recommendations == null ? List.of() : List.copyOf(recommendations);
        goals = goals == null ? List.of() : List.copyOf(goals);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }
}
