package com.openfinance.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import com.openfinance.core.normalize.FiNormalizer;
import com.openfinance.core.normalize.SourceAccount;
import com.openfinance.generator.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Writes the data the edge-forecast backtest runs on (evaluation/forecast/backtest.ts): synthetic customers over a
 * longer window than the services load, normalised by the real normaliser, as deposit-transaction CSVs in the same
 * columns as the rag-service transactions export. Runs only when asked:
 * <pre>
 * mvn -q -pl fi-core -am test -Dtest=ForecastBacktestData -Dsurefire.failIfNoSpecifiedTests=false \
 *     -Detherm.backtest.out=$PWD/evaluation/forecast/data
 * </pre>
 * Optional: -Detherm.backtest.users (30), -Detherm.backtest.seed (42), -Detherm.backtest.from (2023-10-01),
 * -Detherm.backtest.to (2026-09-30). The held-out set uses -Detherm.backtest.seed=7 and evaluation/forecast/heldout.
 */
@EnabledIfSystemProperty(named = "etherm.backtest.out", matches = ".+")
class ForecastBacktestData {

    private static final List<String> HEADER = List.of("date", "record_id", "provider", "account", "type", "category",
            "direction", "amount_inr", "signed_amount_inr", "description", "counterparty");

    @Test
    void writeCustomers() throws IOException {
        Path out = Path.of(System.getProperty("etherm.backtest.out"));
        int users = Integer.parseInt(System.getProperty("etherm.backtest.users", "30"));
        long seed = Long.parseLong(System.getProperty("etherm.backtest.seed", "42"));
        LocalDate from = LocalDate.parse(System.getProperty("etherm.backtest.from", "2023-10-01"));
        LocalDate to = LocalDate.parse(System.getProperty("etherm.backtest.to", "2026-09-30"));
        SyntheticDataGenerator generator = new SyntheticDataGenerator(seed, from, to);
        FiNormalizer normalizer = new FiNormalizer();
        Files.createDirectories(out);
        for (int i = 0; i < users; i++) {
            JsonNode response = generator.generate(i);
            String customer = response.get("customerId").asText();
            StringBuilder csv = new StringBuilder(row(HEADER));
            for (JsonNode fip : response.get("FI")) {
                for (JsonNode d : fip.get("data")) {
                    SourceAccount source = new SourceAccount(customer, "CN-BACKTEST", fip.get("fipID").asText(),
                            d.get("linkRefNumber").asText(), d.get("maskedAccNumber").asText(), d.at("/decryptedFI/account"));
                    if (!"deposit".equals(source.account().path("type").asText())) {
                        continue;
                    }
                    normalizer.normalize(source).records().stream()
                            .filter(r -> r.kind() == RecordKind.TRANSACTION && r.fiType() == FiType.DEPOSIT)
                            .sorted(Comparator.comparing(FinancialRecord::date).thenComparing(FinancialRecord::recordId))
                            .forEach(r -> csv.append(row(cells(r))));
                }
            }
            Path file = out.resolve(customer.split("@")[0] + ".csv");
            Files.writeString(file, csv);
            assertThat(Files.size(file)).isPositive();
        }
    }

    private static List<String> cells(FinancialRecord r) {
        BigDecimal signed = r.direction() == Direction.DEBIT ? r.amount().negate() : r.amount();
        return List.of(r.date().toString(), r.recordId(), r.fipId(), r.accountId(), r.fiType().name(), r.category().name(),
                r.direction().name(), money(r.amount()), money(signed), r.description(),
                r.counterparty() == null ? "" : r.counterparty());
    }

    private static String money(BigDecimal v) {
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    private static String row(List<String> cells) {
        return cells.stream()
                .map(c -> c.indexOf(',') >= 0 || c.indexOf('"') >= 0 ? '"' + c.replace("\"", "\"\"") + '"' : c)
                .collect(Collectors.joining(",", "", "\r\n"));
    }
}
