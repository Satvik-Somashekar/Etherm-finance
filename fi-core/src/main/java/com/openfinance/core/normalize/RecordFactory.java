package com.openfinance.core.normalize;

import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;

/** Builds records that share one source account's context. */
final class RecordFactory {

    private final SourceAccount source;
    private final FiType fiType;

    RecordFactory(SourceAccount source, FiType fiType) {
        this.source = source;
        this.fiType = fiType;
    }

    FinancialRecord record(String localId, RecordKind kind, Category category, Direction direction, LocalDate date,
                           BigDecimal amount, String description, String counterparty, Map<String, String> attributes) {
        return new FinancialRecord(source.linkRefNumber() + ":" + localId, source.customerId(), source.linkRefNumber(),
                source.fipId(), fiType, kind, category, direction, date, amount.abs(), description, counterparty,
                attributes);
    }
}
