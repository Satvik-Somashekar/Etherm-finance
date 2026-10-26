package com.openfinance.core.oracle;

import com.openfinance.core.compute.DateRange;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

/**
 * What the model asks Java to compute: filter, then group, aggregate, compare and rank. It is a
 * structured request, never SQL or free text, so the consent scope can be applied to it before any
 * record is read. This generalises the current {@code QueryPlan}, which stays in place until the
 * agent loop replaces the keyword parser.
 */
public record AgentQueryPlan(
        Filter filter,
        List<Dimension> groupBy,
        List<Aggregation> aggregate,
        Compare compare,
        Rank rank) {

    public AgentQueryPlan {
        groupBy = groupBy == null ? List.of() : List.copyOf(groupBy);
        aggregate = aggregate == null || aggregate.isEmpty() ? List.of(Aggregation.SUM) : List.copyOf(aggregate);
    }

    /** Every field is optional; an empty set or null means no restriction on it. */
    public record Filter(
            Set<FiType> fiTypes,
            Set<String> accountIds,
            Set<String> instruments,
            Set<Category> categories,
            Set<String> merchants,
            DateRange dateRange,
            AmountRange amountRange) {
    }

    public record AmountRange(BigDecimal min, BigDecimal max) {
    }

    public enum Dimension { MONTH, CATEGORY, ACCOUNT, INSTRUMENT, FI_TYPE }

    public enum Aggregation { SUM, COUNT, AVG, MIN, MAX, LATEST, CHANGE, SHARE }

    /** Compare the filtered period or group against another; the other side is filled in by Java. */
    public record Compare(Kind kind, DateRange against, String groupKey) {
        public enum Kind { PERIOD_VS_PERIOD, GROUP_VS_GROUP }
    }

    public record Rank(int topN, Direction direction) {
        public enum Direction { LARGEST, SMALLEST }
    }
}
