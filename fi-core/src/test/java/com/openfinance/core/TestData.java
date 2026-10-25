package com.openfinance.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.normalize.FiNormalizer;
import com.openfinance.core.normalize.SourceAccount;
import com.openfinance.generator.SyntheticDataGenerator;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/** Generated fetch responses and their normalised records, for tests. */
public final class TestData {

    public static final LocalDate FROM = LocalDate.of(2025, 10, 1);
    public static final LocalDate TO = LocalDate.of(2026, 9, 30);
    public static final SyntheticDataGenerator GENERATOR = new SyntheticDataGenerator(42, FROM, TO);

    private TestData() {
    }

    public static List<SourceAccount> sources(int user) {
        JsonNode response = GENERATOR.generate(user);
        List<SourceAccount> out = new ArrayList<>();
        for (JsonNode fip : response.get("FI")) {
            for (JsonNode d : fip.get("data")) {
                out.add(new SourceAccount(response.get("customerId").asText(), "CN-TEST", fip.get("fipID").asText(),
                        d.get("linkRefNumber").asText(), d.get("maskedAccNumber").asText(), d.at("/decryptedFI/account")));
            }
        }
        return out;
    }

    public static List<FinancialRecord> records(int user) {
        FiNormalizer normalizer = new FiNormalizer();
        return sources(user).stream().flatMap(s -> normalizer.normalize(s).records().stream()).toList();
    }
}
