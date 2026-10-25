package com.openfinance.normalization;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.consent.ConsentArtifact;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;

/** Reads consents and consent-scoped raw accounts from the ingestion service. */
@Component
public class IngestionClient {

    private final RestClient http;

    public IngestionClient(RestClient.Builder builder, @Value("${etherm.services.ingestion}") String baseUrl) {
        this.http = builder.baseUrl(baseUrl).build();
    }

    public ConsentArtifact consent(String consentId) {
        return http.get().uri("/api/consents/{id}", consentId).retrieve().body(ConsentArtifact.class);
    }

    public List<ConsentArtifact> consents(String customerId) {
        return http.get().uri(b -> b.path("/api/consents").queryParam("customerId", customerId).build())
                .retrieve().body(new ParameterizedTypeReference<>() { });
    }

    /** Raw accounts fetched under the consent; ingestion answers 403 once the consent is not active. */
    public List<JsonNode> fetchedAccounts(String consentId) {
        return http.get().uri("/api/consents/{id}/accounts", consentId).retrieve()
                .body(new ParameterizedTypeReference<>() { });
    }
}
