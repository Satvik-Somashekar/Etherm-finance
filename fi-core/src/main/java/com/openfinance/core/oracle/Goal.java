package com.openfinance.core.oracle;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** A target amount by a date. Progress is computed from the linked accounts' consented balances. */
public record Goal(String goalId, String name, BigDecimal targetAmount, LocalDate targetDate, List<String> accountIds) {

    public Goal {
        accountIds = accountIds == null ? List.of() : List.copyOf(accountIds);
    }

    /** Computed by Java; every number is a {@link ResultObject} so it carries its records. */
    public record Progress(String goalId, ResultObject current, ResultObject remaining,
                           ResultObject requiredMonthly, Status status) {
        public enum Status { ON_TRACK, OFF_TRACK, ACHIEVED, NO_DATA }
    }
}
