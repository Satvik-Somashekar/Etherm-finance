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
 */
public record QueryRequest(String question, String customerId, List<String> consentIds, Set<FiType> scope) {
}
