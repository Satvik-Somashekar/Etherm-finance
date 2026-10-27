package com.openfinance.rag;

import com.openfinance.core.model.FiType;

import java.util.List;
import java.util.Set;

/**
 * Either name the consents to answer under, or a customer, in which case every active consent
 * the customer holds is used.
 *
 * @param scope optional narrowing to some FI types (a Knowledge Base section). It only ever removes
 *              data from what the consents allow; null or empty means no narrowing
 * @param history the customer's earlier questions in this conversation, oldest first; used only to resolve
 *                what "it" means in a follow-up
 */
public record QueryRequest(String question, String customerId, List<String> consentIds, Set<FiType> scope,
                           List<String> history) {

    public QueryRequest(String question, String customerId, List<String> consentIds, Set<FiType> scope) {
        this(question, customerId, consentIds, scope, null);
    }
}
