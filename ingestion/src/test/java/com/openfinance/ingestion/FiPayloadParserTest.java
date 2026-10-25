package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openfinance.core.model.FiType;
import com.openfinance.generator.SyntheticDataGenerator;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiPayloadParserTest {

    private final FiPayloadParser parser = new FiPayloadParser(Clock.fixed(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC));
    private final SyntheticDataGenerator generator =
            new SyntheticDataGenerator(42, LocalDate.of(2025, 10, 1), LocalDate.of(2026, 9, 30));

    @Test
    void splitsGeneratedResponseIntoAccountsOfEveryType() {
        List<RawAccount> accounts = parser.parse(generator.generate(0));
        assertThat(accounts).hasSize(6);
        assertThat(accounts).extracting(RawAccount::fiType)
                .containsExactlyInAnyOrder(FiType.DEPOSIT, FiType.MUTUAL_FUNDS, FiType.INSURANCE_POLICIES,
                        FiType.INSURANCE_POLICIES, FiType.EPF, FiType.EQUITIES);
        assertThat(accounts).allSatisfy(a -> assertThat(a.customerId()).isEqualTo("user001@etherm-aa"));
    }

    @Test
    void rejectsUnsupportedAccountTypeAndMissingCustomer() {
        ObjectNode payload = generator.generate(0);
        payload.remove("customerId");
        ((ObjectNode) payload.at("/FI/0/data/0/decryptedFI/account")).put("type", "credit_card");
        assertThatThrownBy(() -> parser.parse(payload))
                .isInstanceOf(InvalidFiPayloadException.class)
                .satisfies(e -> assertThat(((InvalidFiPayloadException) e).problems())
                        .anyMatch(p -> p.contains("customerId"))
                        .anyMatch(p -> p.contains("credit_card")));
    }

    @Test
    void rejectsEmptyFiArray() {
        ObjectNode payload = new ObjectMapper().createObjectNode().put("ver", "2.0.0").put("customerId", "x");
        payload.putArray("FI");
        assertThatThrownBy(() -> parser.parse(payload)).isInstanceOf(InvalidFiPayloadException.class);
    }
}
