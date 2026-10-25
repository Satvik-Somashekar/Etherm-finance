package com.openfinance.ingestion;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.FiType;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates the structure of a decrypted FI fetch response and splits it into raw accounts.
 * A payload with any structural problem is rejected as a whole, so partial data never lands.
 */
@Component
public class FiPayloadParser {

    private final Clock clock;

    public FiPayloadParser(Clock clock) {
        this.clock = clock;
    }

    public List<RawAccount> parse(JsonNode payload) {
        List<String> problems = new ArrayList<>();
        String customerId = text(payload, "customerId");
        String txnId = text(payload, "txnid");
        if (customerId == null) {
            problems.add("customerId is required");
        }
        if (text(payload, "ver") == null) {
            problems.add("ver is required");
        }
        JsonNode fis = payload.get("FI");
        if (fis == null || !fis.isArray() || fis.isEmpty()) {
            problems.add("FI must be a non-empty array");
            throw new InvalidFiPayloadException(problems);
        }

        Instant now = clock.instant();
        List<RawAccount> accounts = new ArrayList<>();
        for (int i = 0; i < fis.size(); i++) {
            JsonNode fip = fis.get(i);
            String fipId = text(fip, "fipID");
            if (fipId == null) {
                problems.add("FI[" + i + "].fipID is required");
            }
            JsonNode data = fip.get("data");
            if (data == null || !data.isArray()) {
                problems.add("FI[" + i + "].data must be an array");
                continue;
            }
            for (int j = 0; j < data.size(); j++) {
                String where = "FI[" + i + "].data[" + j + "]";
                JsonNode entry = data.get(j);
                JsonNode account = entry.at("/decryptedFI/account");
                if (account.isMissingNode() || !account.isObject()) {
                    problems.add(where + ".decryptedFI.account is required");
                    continue;
                }
                String type = text(account, "type");
                FiType fiType = FiType.fromSchemaType(type).orElse(null);
                if (fiType == null) {
                    problems.add(where + " has unsupported account type '" + type + "'");
                    continue;
                }
                String linkRef = text(entry, "linkRefNumber");
                if (linkRef == null) {
                    problems.add(where + ".linkRefNumber is required");
                }
                JsonNode txns = account.at("/transactions/transaction");
                if (!txns.isMissingNode() && !txns.isArray()) {
                    problems.add(where + " transactions.transaction must be an array");
                }
                accounts.add(new RawAccount(customerId, null, fipId, linkRef, text(entry, "maskedAccNumber"),
                        fiType, txnId, now, account));
            }
        }
        if (!problems.isEmpty()) {
            throw new InvalidFiPayloadException(problems);
        }
        return accounts;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
