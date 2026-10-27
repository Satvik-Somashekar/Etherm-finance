package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.DateRange;
import com.openfinance.core.compute.Figure;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryEngine;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.compute.RecordFilter;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * CSV downloads. Both go through the same Allow -> Find -> Add path as the Oracle: only records the
 * customer's active consents allow are exported, and the report's figures come from the same
 * compute layer, so a report and an Oracle answer for the same period agree.
 */
@Service
public class ExportService {

    private static final List<Category> SPENDING_COLUMNS = List.of(Category.values()).stream()
            .filter(Category.SPENDING::contains).toList();

    private final UpstreamClients upstream;
    private final QueryService queries;
    private final QueryEngine engine = new QueryEngine();

    public ExportService(UpstreamClients upstream, QueryService queries) {
        this.upstream = upstream;
        this.queries = queries;
    }

    /** One row per transaction, oldest first. Every filter is optional. */
    public String transactionsCsv(String customerId, LocalDate from, LocalDate to, Set<Category> categories, Set<FiType> fiTypes) {
        List<FinancialRecord> records = transactions(customerId).stream()
                .filter(r -> from == null || !r.date().isBefore(from))
                .filter(r -> to == null || !r.date().isAfter(to))
                .filter(r -> categories == null || categories.isEmpty() || categories.contains(r.category()))
                .filter(r -> fiTypes == null || fiTypes.isEmpty() || fiTypes.contains(r.fiType()))
                .sorted(Comparator.comparing(FinancialRecord::date).thenComparing(FinancialRecord::recordId))
                .toList();
        Csv csv = new Csv();
        csv.row(List.of("date", "record_id", "provider", "account", "type", "category", "direction", "amount_inr",
                "signed_amount_inr", "description", "counterparty"));
        for (FinancialRecord r : records) {
            BigDecimal signed = r.direction() == Direction.DEBIT ? r.amount().negate() : r.amount();
            csv.row(List.of(r.date().toString(), Csv.text(r.recordId()), Csv.text(r.fipId()), Csv.text(r.accountId()),
                    r.fiType().name(), r.category().name(), r.direction() == null ? "" : r.direction().name(),
                    Csv.money(r.amount()), Csv.money(signed), Csv.text(r.description()), Csv.text(r.counterparty())));
        }
        return csv.toString();
    }

    /** Month by month income, spending, saved and savings rate, then spending by category, plus a total row. */
    public String reportCsv(String customerId, LocalDate from, LocalDate to) {
        List<FinancialRecord> records = transactions(customerId);
        Csv csv = new Csv();
        List<String> header = new ArrayList<>(List.of("month", "income_inr", "spending_inr", "saved_inr", "savings_rate_percent"));
        SPENDING_COLUMNS.forEach(c -> header.add(c.name().toLowerCase() + "_inr"));
        csv.row(header);

        LocalDate first = records.stream().map(FinancialRecord::date).filter(d -> from == null || !d.isBefore(from))
                .min(Comparator.naturalOrder()).orElse(null);
        LocalDate last = records.stream().map(FinancialRecord::date).filter(d -> to == null || !d.isAfter(to))
                .max(Comparator.naturalOrder()).orElse(null);
        if (first == null || last == null || last.isBefore(first)) {
            return csv.toString();
        }
        for (YearMonth m = YearMonth.from(first); !m.isAfter(YearMonth.from(last)); m = m.plusMonths(1)) {
            LocalDate start = max(m.atDay(1), first);
            LocalDate end = min(m.atEndOfMonth(), last);
            csv.row(reportRow(m.toString(), new DateRange(start, end, m.toString()), records));
        }
        csv.row(reportRow("total", new DateRange(first, last, "total"), records));
        return csv.toString();
    }

    private List<String> reportRow(String label, DateRange period, List<FinancialRecord> records) {
        ComputedAnswer savings = engine.compute(QueryPlan.of(Intent.SAVINGS_RATE).withPeriod(period), records);
        List<String> row = new ArrayList<>(List.of(label, value(savings, "income"), value(savings, "spending"),
                value(savings, "saved"), savings.figure("savingsRate") == null ? ""
                        : savings.figure("savingsRate").value().setScale(1, java.math.RoundingMode.HALF_UP).toPlainString()));
        for (Category c : SPENDING_COLUMNS) {
            ComputedAnswer a = engine.compute(
                    QueryPlan.of(Intent.SPEND_CATEGORY).withCategories(Set.of(c)).withPeriod(period), records);
            row.add(value(a, "total"));
        }
        return row;
    }

    private static String value(ComputedAnswer answer, String key) {
        Figure f = answer.figure(key);
        return Csv.money(f == null ? BigDecimal.ZERO : f.value());
    }

    private List<FinancialRecord> transactions(String customerId) {
        List<String> consentIds = queries.consents(new QueryRequest(null, customerId, null, null));
        RecordFilter filter = new RecordFilter(Set.of(), Set.of(RecordKind.TRANSACTION), Set.of(), null, null, null);
        return upstream.retrieve(consentIds, filter).records();
    }

    private static LocalDate max(LocalDate a, LocalDate b) {
        return a.isAfter(b) ? a : b;
    }

    private static LocalDate min(LocalDate a, LocalDate b) {
        return a.isBefore(b) ? a : b;
    }
}
