package com.openfinance.core.compute;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

/** An inclusive date range with the human label it was parsed from ("last quarter"). */
public record DateRange(LocalDate from, LocalDate to, String label) {

    public DateRange {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to is before from");
        }
    }

    public boolean contains(LocalDate date) {
        return !date.isBefore(from) && !date.isAfter(to);
    }

    /** The range of equal length immediately before this one. */
    public DateRange previous() {
        long days = ChronoUnit.DAYS.between(from, to) + 1;
        // Calendar-aligned ranges (a whole month or quarter) step back by whole months.
        if (from.getDayOfMonth() == 1 && to.plusDays(1).getDayOfMonth() == 1) {
            long months = ChronoUnit.MONTHS.between(from, to.plusDays(1));
            return new DateRange(from.minusMonths(months), from.minusDays(1), "the period before");
        }
        return new DateRange(from.minusDays(days), from.minusDays(1), "the period before");
    }
}
