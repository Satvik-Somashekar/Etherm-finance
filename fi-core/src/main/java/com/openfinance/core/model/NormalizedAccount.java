package com.openfinance.core.model;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Account-level view shared by all FI types.
 *
 * @param currentValue the account's reported value; for insurance this is zero, because a policy's
 *                     sum assured is cover, not an asset the user holds
 */
public record NormalizedAccount(
        String accountId,
        String customerId,
        String consentId,
        String fipId,
        FiType fiType,
        String maskedAccNumber,
        String label,
        BigDecimal currentValue,
        LocalDate valueAsOf,
        int recordCount) {
}
