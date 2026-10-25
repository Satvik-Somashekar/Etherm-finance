package com.openfinance.core.compute;

import java.math.BigDecimal;
import java.util.List;

/**
 * A number computed in Java, with the records it was computed from. This is the only channel
 * through which figures reach the UI.
 */
public record Figure(String key, String label, BigDecimal value, Unit unit, List<String> recordIds) {

    public enum Unit {
        INR,
        COUNT,
        PERCENT
    }

    public Figure {
        recordIds = List.copyOf(recordIds);
    }
}
