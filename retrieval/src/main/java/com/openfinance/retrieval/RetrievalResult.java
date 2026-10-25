package com.openfinance.retrieval;

import com.openfinance.core.model.FinancialRecord;

import java.util.List;

/**
 * @param visible  records the consents allowed, before the query filter
 * @param records  records that also matched the filter
 */
public record RetrievalResult(List<String> consentIds, int visible, List<FinancialRecord> records) {
}
