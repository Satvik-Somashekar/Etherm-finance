package com.openfinance.retrieval;

import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.model.FinancialRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Consent lookups go to ingestion (the consent manager); records come from normalization. */
@Component
public class UpstreamClients {

    private final RestClient ingestion;
    private final RestClient normalization;

    public UpstreamClients(RestClient.Builder builder,
                           @Value("${etherm.services.ingestion}") String ingestionUrl,
                           @Value("${etherm.services.normalization}") String normalizationUrl) {
        this.ingestion = builder.clone().baseUrl(ingestionUrl).build();
        this.normalization = builder.clone().baseUrl(normalizationUrl).build();
    }

    public ConsentArtifact consent(String consentId) {
        return ingestion.get().uri("/api/consents/{id}", consentId).retrieve().body(ConsentArtifact.class);
    }

    public List<FinancialRecord> records(String consentId) {
        return normalization.get().uri("/api/consents/{id}/records", consentId).retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }
}
