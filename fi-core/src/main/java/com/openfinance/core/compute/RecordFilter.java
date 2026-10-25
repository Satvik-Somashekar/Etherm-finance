package com.openfinance.core.compute;

import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;

import java.time.LocalDate;
import java.util.Set;

/**
 * The structured retrieval request: which records the compute step needs. Empty sets and null
 * dates mean "no restriction on this field".
 */
public record RecordFilter(
        Set<FiType> fiTypes,
        Set<RecordKind> kinds,
        Set<Category> categories,
        Direction direction,
        LocalDate from,
        LocalDate to) {

    public RecordFilter {
        fiTypes = fiTypes == null ? Set.of() : Set.copyOf(fiTypes);
        kinds = kinds == null ? Set.of() : Set.copyOf(kinds);
        categories = categories == null ? Set.of() : Set.copyOf(categories);
    }

    public boolean matches(FinancialRecord r) {
        return (fiTypes.isEmpty() || fiTypes.contains(r.fiType()))
                && (kinds.isEmpty() || kinds.contains(r.kind()))
                && (categories.isEmpty() || categories.contains(r.category()))
                && (direction == null || direction == r.direction())
                && (from == null || !r.date().isBefore(from))
                && (to == null || !r.date().isAfter(to));
    }
}
