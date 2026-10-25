package com.openfinance.core.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * One normalised, citable fact. Every figure Etherm reports is computed from a set of these, and
 * the answer cites them by {@link #recordId()}.
 *
 * @param recordId    stable id: {@code <linkRef>:<source id>}, unique per customer
 * @param accountId   the linked account reference the record came from
 * @param amount      always non-negative; {@link #direction()} carries the sign for transactions
 * @param attributes  type-specific extras kept as strings (isin, units, policy number, ...)
 */
public record FinancialRecord(
        String recordId,
        String customerId,
        String accountId,
        String fipId,
        FiType fiType,
        RecordKind kind,
        Category category,
        Direction direction,
        LocalDate date,
        BigDecimal amount,
        String description,
        String counterparty,
        Map<String, String> attributes) {

    public FinancialRecord {
        Objects.requireNonNull(recordId, "recordId");
        Objects.requireNonNull(fiType, "fiType");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(category, "category");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(amount, "amount");
        if (amount.signum() < 0) {
            throw new IllegalArgumentException("amount must be non-negative: " + recordId);
        }
        attributes = attributes == null ? Map.of() : Map.copyOf(attributes);
    }
}
