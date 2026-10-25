package com.openfinance.retrieval;

import com.openfinance.core.compute.RecordFilter;

import java.util.List;

/**
 * @param consentIds every consent the caller is acting under; each is checked independently
 * @param filter     the structured description of the records wanted
 */
public record RetrievalRequest(List<String> consentIds, RecordFilter filter) {
}
