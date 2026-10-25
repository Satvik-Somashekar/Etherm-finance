package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.FiType;

import java.time.Instant;

/**
 * One FI account as held by a FIP, or as fetched under a consent, before normalisation.
 *
 * @param consentId null while the account sits at the FIP; set once it is fetched under a consent
 * @param account   the decrypted ReBIT {@code Account} node
 */
public record RawAccount(
        String customerId,
        String consentId,
        String fipId,
        String linkRefNumber,
        String maskedAccNumber,
        FiType fiType,
        String fetchTxnId,
        Instant ingestedAt,
        JsonNode account) {

    public RawAccount fetchedUnder(String consent, Instant at, JsonNode trimmed) {
        return new RawAccount(customerId, consent, fipId, linkRefNumber, maskedAccNumber, fiType, fetchTxnId, at, trimmed);
    }
}
