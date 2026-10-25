package com.openfinance.core.compute;

import java.math.BigDecimal;
import java.util.List;

public record BreakdownItem(String label, BigDecimal value, List<String> recordIds) {

    public BreakdownItem {
        recordIds = List.copyOf(recordIds);
    }
}
