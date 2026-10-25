package com.openfinance.rag;

import java.util.List;

/**
 * Either name the consents to answer under, or a customer, in which case every active consent
 * the customer holds is used.
 */
public record QueryRequest(String question, String customerId, List<String> consentIds) {
}
