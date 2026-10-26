package com.openfinance.core.oracle;

import com.openfinance.core.compute.DateRange;

import java.math.BigDecimal;
import java.util.List;

/**
 * One computed number. This is the only form in which a personal figure may reach the model or the
 * UI. When nothing can be computed, {@code value} is null and {@code emptyReason} says why, so
 * "no records", "outside your consent" and "the data has no such field" stay distinct answers.
 */
public record ResultObject(
        String resultId,
        String label,
        BigDecimal value,
        Unit unit,
        DateRange period,
        List<String> recordIds,
        EmptyReason emptyReason) {

    public ResultObject {
        recordIds = recordIds == null ? List.of() : List.copyOf(recordIds);
    }

    public enum Unit { INR, COUNT, PERCENT, UNITS, MONTHS, DAYS }

    public enum EmptyReason {
        /** The query was allowed but no record matched. */
        NO_MATCHING_RECORDS,
        /** Matching data exists but is outside the active consents. */
        OUTSIDE_CONSENT,
        /** Records matched but lack a field the metric needs (for example no purchase date). */
        FIELD_MISSING,
        /** The question needs data Etherm does not hold (credit score, loans, market data). */
        NOT_IN_DATA
    }
}
