package com.openfinance.rag;

import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

/**
 * Everything the Dashboard shows besides the account balances, computed from the records the
 * customer's active consents allow.
 *
 * @param history  month-end net worth, rebuilt from transactions at the latest prices
 * @param activity the most recent transactions across every FI type
 * @param insights answers to fixed questions, run through the same pipeline as the Oracle
 */
public record OverviewResponse(
        String customerId,
        List<String> consentIds,
        LocalDate asOf,
        List<HistoryPoint> history,
        List<Activity> activity,
        List<QueryResponse> insights) {

    /** @param records how many records the value was computed from */
    public record HistoryPoint(YearMonth month, LocalDate date, BigDecimal value, int records) {
    }

    public record Activity(String recordId, String fipId, FiType fiType, LocalDate date, BigDecimal amount,
                           Direction direction, Category category, String description, String counterparty) {
    }
}
