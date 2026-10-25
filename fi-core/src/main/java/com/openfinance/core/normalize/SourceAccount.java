package com.openfinance.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A decrypted ReBIT {@code Account} node plus the fetch context it arrived with.
 */
public record SourceAccount(
        String customerId,
        String consentId,
        String fipId,
        String linkRefNumber,
        String maskedAccNumber,
        JsonNode account) {
}
