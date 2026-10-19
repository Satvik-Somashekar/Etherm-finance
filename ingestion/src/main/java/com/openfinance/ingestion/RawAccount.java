package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;

/**
 * One FI account exactly as received from a FIP, before normalisation.
 *
 * @param account the decrypted ReBIT {@code Account} node, untouched
 */
public record RawAccount(
        String customerId,
        String fipId,
        String linkRefNumber,
        String maskedAccNumber,
        FiType fiType,
        String fetchTxnId,
        Instant ingestedAt,
        JsonNode account) {
}
