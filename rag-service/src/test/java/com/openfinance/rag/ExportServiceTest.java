package com.openfinance.rag;

import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ExportServiceTest {

    private UpstreamClients upstream;
    private QueryService queries;
    private ExportService exports;

    private static FinancialRecord txn(String id, String date, Category category, Direction direction, String amount,
                                       String description, String counterparty) {
        return new FinancialRecord(id, "u", "A1", "BANK", FiType.DEPOSIT, RecordKind.TRANSACTION, category, direction,
                LocalDate.parse(date), new BigDecimal(amount), description, counterparty, Map.of());
    }

    @BeforeEach
    void setUp() {
        upstream = mock(UpstreamClients.class);
        queries = mock(QueryService.class);
        exports = new ExportService(upstream, queries);
        when(queries.consents(any())).thenReturn(List.of("CN-1"));
        when(upstream.retrieve(any(), any())).thenReturn(new UpstreamClients.Retrieved(List.of("CN-1"), 5, List.of(
                txn("A1:3", "2026-07-05", Category.GROCERIES, Direction.DEBIT, "10000.00", "UPI/FRESHMART/x", "FreshMart, Pune"),
                txn("A1:1", "2026-07-01", Category.SALARY, Direction.CREDIT, "100000.00", "NEFT/SALARY/ACME", "ACME"),
                txn("A1:2", "2026-07-03", Category.SIP_DEBIT, Direction.DEBIT, "5000.00", "ACH/SIP/FUND", "FUND"),
                txn("A1:4", "2026-08-02", Category.OTHER_DEBIT, Direction.DEBIT, "250.50", "=HYPERLINK(\"http://x\")", "@evil"),
                txn("A1:5", "2026-08-01", Category.SALARY, Direction.CREDIT, "100000.00", "NEFT/SALARY/ACME", "ACME"))));
    }

    @Test
    void transactionsAreSortedSignedAndDefused() {
        List<String> lines = exports.transactionsCsv("u", null, null, null, null).lines().toList();
        assertThat(lines.getFirst()).startsWith("date,record_id,provider");
        assertThat(lines).hasSize(6);
        assertThat(lines.get(1)).startsWith("2026-07-01,A1:1,");
        assertThat(lines.get(2)).contains("A1:2").contains("DEBIT,5000.00,-5000.00");
        assertThat(lines.get(3)).contains("\"FreshMart, Pune\"");
        // Formula-looking text from the data is neutralised, numbers stay plain.
        assertThat(lines.get(5)).contains("\"'=HYPERLINK(\"\"http://x\"\")\"").contains("'@evil").contains("-250.50");
    }

    @Test
    void filtersNarrowTheRows() {
        String csv = exports.transactionsCsv("u", LocalDate.parse("2026-08-01"), null, java.util.Set.of(Category.SALARY), java.util.Set.of(FiType.DEPOSIT));
        assertThat(csv.lines().toList()).hasSize(2);
        assertThat(csv).contains("A1:5");
    }

    @Test
    void reportUsesTheOracleDefinitions() {
        List<String> lines = exports.reportCsv("u", null, null).lines().toList();
        assertThat(lines.getFirst()).startsWith("month,income_inr,spending_inr,saved_inr,savings_rate_percent,rent_inr");
        // July: salary 100000, spending 10000 (the SIP is not spending), saved 90000 at 90.0%.
        assertThat(lines.get(1)).startsWith("2026-07,100000.00,10000.00,90000.00,90.0,");
        assertThat(lines.get(1)).contains(",10000.00,");
        // August: salary 100000, spending 250.50.
        assertThat(lines.get(2)).startsWith("2026-08,100000.00,250.50,99749.50,99.7,");
        assertThat(lines.get(3)).startsWith("total,200000.00,10250.50,189749.50,94.9,");
    }

    @Test
    void reportRangeLimitsTheMonths() {
        List<String> lines = exports.reportCsv("u", LocalDate.parse("2026-08-01"), null).lines().toList();
        assertThat(lines).hasSize(3);
        assertThat(lines.get(1)).startsWith("2026-08,");
    }

    @Test
    void noActiveConsentExportsNothing() {
        when(queries.consents(any())).thenThrow(new NoActiveConsentException("u"));
        assertThatThrownBy(() -> exports.transactionsCsv("u", null, null, null, null))
                .isInstanceOf(NoActiveConsentException.class);
        assertThatThrownBy(() -> exports.reportCsv("u", null, null)).isInstanceOf(NoActiveConsentException.class);
    }
}
