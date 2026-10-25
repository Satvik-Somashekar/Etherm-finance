package com.openfinance.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

class SyntheticDataGeneratorTest {

    private static final LocalDate FROM = LocalDate.of(2025, 10, 1);
    private static final LocalDate TO = LocalDate.of(2026, 9, 30);

    private final SyntheticDataGenerator generator = new SyntheticDataGenerator(42, FROM, TO);

    @Test
    void sameSeedProducesIdenticalOutput() {
        ObjectNode a = generator.generate(0);
        ObjectNode b = new SyntheticDataGenerator(42, FROM, TO).generate(0);
        assertThat(a).isEqualTo(b);
        assertThat(new SyntheticDataGenerator(7, FROM, TO).generate(0)).isNotEqualTo(a);
    }

    @Test
    void coversAllFiveFiTypes() {
        Set<String> types = new TreeSet<>();
        accounts(generator.generate(1)).forEach(acc -> types.add(acc.get("type").asText()));
        assertThat(types).containsExactly("deposit", "epf", "equities", "insurance_policies", "mutual_funds");
    }

    @Test
    void depositRunningBalanceIsConsistent() {
        for (int user = 0; user < 5; user++) {
            JsonNode deposit = accountOfType(generator.generate(user), "deposit");
            BigDecimal prev = null;
            for (JsonNode t : deposit.at("/transactions/transaction")) {
                BigDecimal amount = new BigDecimal(t.get("amount").asText());
                BigDecimal balance = new BigDecimal(t.get("currentBalance").asText());
                if (prev != null) {
                    BigDecimal expected = "CREDIT".equals(t.get("type").asText()) ? prev.add(amount) : prev.subtract(amount);
                    assertThat(balance).isEqualByComparingTo(expected);
                }
                prev = balance;
            }
            assertThat(new BigDecimal(deposit.at("/summary/currentBalance").asText())).isEqualByComparingTo(prev);
        }
    }

    @Test
    void sipAndPremiumDebitsAppearInDepositAccount() {
        ObjectNode response = generator.generate(2);
        JsonNode deposit = accountOfType(response, "deposit");
        long sipDebits = count(deposit, "ACH/SIP/");
        long premiumDebits = count(deposit, "ACH/PREMIUM/");
        long mfBuys = 0;
        for (JsonNode t : accountOfType(response, "mutual_funds").at("/transactions/transaction")) {
            if ("BUY".equals(t.get("type").asText())) {
                mfBuys++;
            }
        }
        long premiums = 0;
        for (JsonNode acc : accounts(response)) {
            if ("insurance_policies".equals(acc.get("type").asText())) {
                premiums += acc.at("/transactions/transaction").size();
            }
        }
        assertThat(sipDebits).isEqualTo(mfBuys);
        assertThat(premiumDebits).isEqualTo(premiums);
    }

    @Test
    void transactionsStayInsideTheRequestedWindow() {
        for (JsonNode acc : accounts(generator.generate(3))) {
            for (JsonNode t : acc.at("/transactions/transaction")) {
                String date = firstText(t, "transactionTimestamp", "transactionDate", "txnDate", "transactionDateTime");
                LocalDate d = LocalDate.parse(date.substring(0, 10));
                assertThat(d).isBetween(FROM, TO);
            }
        }
    }

    private static List<JsonNode> accounts(JsonNode response) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode fip : response.get("FI")) {
            for (JsonNode d : fip.get("data")) {
                out.add(d.at("/decryptedFI/account"));
            }
        }
        return out;
    }

    private static JsonNode accountOfType(JsonNode response, String type) {
        return accounts(response).stream().filter(a -> type.equals(a.get("type").asText())).findFirst().orElseThrow();
    }

    private static long count(JsonNode deposit, String narrationPrefix) {
        long n = 0;
        for (JsonNode t : deposit.at("/transactions/transaction")) {
            if (t.get("narration").asText().startsWith(narrationPrefix)) {
                n++;
            }
        }
        return n;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String f : fields) {
            if (node.hasNonNull(f)) {
                return node.get(f).asText();
            }
        }
        throw new AssertionError("no date field in " + node);
    }
}
