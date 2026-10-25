package com.openfinance.core.compute;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.TestData;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import com.openfinance.core.normalize.SourceAccount;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class NetWorthHistoryTest {

    private final List<FinancialRecord> records = TestData.records(0);
    private final List<SourceAccount> raw = TestData.sources(0);
    private final NetWorthHistory history = new NetWorthHistory();

    @Test
    void lastPointIsTodaysNetWorth() {
        List<NetWorthHistory.Point> points = history.compute(records);
        BigDecimal balances = records.stream()
                .filter(r -> r.kind() == RecordKind.BALANCE && NetWorthHistory.ASSET_TYPES.contains(r.fiType()))
                .map(FinancialRecord::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(points.getLast().value()).isEqualByComparingTo(balances);
        assertThat(points).hasSize(12);
    }

    /** The bank's own running balance after the last transaction of each month, read from the raw payload. */
    @Test
    void depositHistoryMatchesTheBanksRunningBalance() {
        List<FinancialRecord> deposits = records.stream().filter(r -> r.fiType() == FiType.DEPOSIT).toList();
        SourceAccount bank = raw.stream().filter(s -> s.account().get("type").asText().equals("deposit"))
                .findFirst().orElseThrow();
        for (NetWorthHistory.Point p : history.compute(deposits)) {
            BigDecimal expected = null;
            for (JsonNode t : bank.account().at("/transactions/transaction")) {
                LocalDate d = LocalDate.parse(t.get("valueDate").asText());
                if (!d.isAfter(p.month().atEndOfMonth())) {
                    expected = new BigDecimal(t.get("currentBalance").asText());
                }
            }
            assertThat(p.value()).as(p.month().toString()).isEqualByComparingTo(expected);
        }
    }

    @Test
    void eachPointCitesTheRecordsItUsed() {
        NetWorthHistory.Point first = history.compute(records).getFirst();
        assertThat(first.month()).isEqualTo(YearMonth.of(2025, 10));
        assertThat(first.recordIds()).allSatisfy(id -> assertThat(records).anyMatch(r -> r.recordId().equals(id)));
        assertThat(first.recordIds()).anyMatch(id -> id.endsWith(":SUMMARY"));
    }
}
