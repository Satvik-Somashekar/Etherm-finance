package com.openfinance.normalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.NormalizedAccount;
import com.openfinance.core.normalize.FiNormalizer;
import com.openfinance.core.normalize.NormalizedData;
import com.openfinance.core.normalize.SourceAccount;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Service
public class NormalizationService {

    private final IngestionClient ingestion;
    private final FiNormalizer normalizer = new FiNormalizer();

    public NormalizationService(IngestionClient ingestion) {
        this.ingestion = ingestion;
    }

    /** Normalises everything fetched under one consent. */
    public List<NormalizedData> normalize(String consentId) {
        List<NormalizedData> out = new ArrayList<>();
        for (JsonNode raw : ingestion.fetchedAccounts(consentId)) {
            out.add(normalizer.normalize(source(raw, consentId)));
        }
        return out;
    }

    private static SourceAccount source(JsonNode raw, String consentId) {
        return new SourceAccount(raw.path("customerId").asText(), consentId, raw.path("fipId").asText(),
                raw.path("linkRefNumber").asText(), raw.path("maskedAccNumber").asText(), raw.path("account"));
    }

    public List<FinancialRecord> records(String consentId) {
        return normalize(consentId).stream().flatMap(d -> d.records().stream()).toList();
    }

    public List<NormalizedAccount> accounts(String consentId) {
        return normalize(consentId).stream().map(NormalizedData::account).toList();
    }

    /** Every account the customer has shared through a currently active consent, with that consent. */
    public List<AccountView> customerAccounts(String customerId) {
        List<AccountView> out = new ArrayList<>();
        for (ConsentArtifact consent : ingestion.consents(customerId)) {
            if (consent.status() != ConsentStatus.ACTIVE) {
                continue;
            }
            for (JsonNode raw : ingestion.fetchedAccounts(consent.consentId())) {
                NormalizedData data = normalizer.normalize(source(raw, consent.consentId()));
                out.add(new AccountView(data.account(), consent, Instant.parse(raw.path("ingestedAt").asText())));
            }
        }
        return out;
    }

    /** @param fetchedAt when the FI fetch under this consent last ran */
    public record AccountView(NormalizedAccount account, ConsentArtifact consent, Instant fetchedAt) {
    }
}
