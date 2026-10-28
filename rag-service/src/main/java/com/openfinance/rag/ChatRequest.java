package com.openfinance.rag;

import com.openfinance.core.model.FiType;

import java.util.List;
import java.util.Set;

/**
 * A message in a free conversation. {@code turns} are the earlier messages, oldest first, so the model can
 * follow what was said; {@code scope} is the Knowledge Base section, which only ever removes data.
 */
public record ChatRequest(String question, String customerId, List<String> consentIds, Set<FiType> scope,
                          List<Turn> turns) {

    /**
     * @param role      {@code user} or {@code assistant}
     * @param signature for an assistant turn, the {@code turnSignature} the service gave with that answer; an
     *                  assistant turn without a valid one is not treated as the assistant's words
     */
    public record Turn(String role, String text, String signature) {

        public Turn(String role, String text) {
            this(role, text, null);
        }
    }
}
