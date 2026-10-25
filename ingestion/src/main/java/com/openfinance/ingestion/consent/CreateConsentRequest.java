package com.openfinance.ingestion.consent;

import com.openfinance.core.model.FiType;

import java.time.LocalDate;
import java.util.Set;

/**
 * What the customer is asked to approve.
 *
 * @param fipIds       limit the fetch to these providers; empty means every FIP holding a consented type
 * @param validForDays how long the grant lasts once created; defaults to 365
 */
public record CreateConsentRequest(
        String customerId,
        Set<FiType> fiTypes,
        Set<String> fipIds,
        String purposeCode,
        String purposeText,
        LocalDate dataFrom,
        LocalDate dataTo,
        Integer validForDays) {
}
