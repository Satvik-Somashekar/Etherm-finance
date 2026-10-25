package com.openfinance.rag;

import com.openfinance.core.compute.RecordFilter;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.model.FinancialRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class UpstreamClients {

    private final RestClient ingestion;
    private final RestClient retrieval;

    public UpstreamClients(RestClient.Builder builder,
                           @Value("${etherm.services.ingestion}") String ingestionUrl,
                           @Value("${etherm.services.retrieval}") String retrievalUrl) {
        this.ingestion = builder.clone().baseUrl(ingestionUrl).build();
        this.retrieval = builder.clone().baseUrl(retrievalUrl).build();
    }

    public List<ConsentArtifact> consents(String customerId) {
        return ingestion.get().uri(b -> b.path("/api/consents").queryParam("customerId", customerId).build())
                .retrieve().body(new ParameterizedTypeReference<>() { });
    }

    public Retrieved retrieve(List<String> consentIds, RecordFilter filter) {
        return retrieval.post().uri("/api/retrieve")
                .body(Map.of("consentIds", consentIds, "filter", filter))
                .retrieve().body(Retrieved.class);
    }

    public record Retrieved(List<String> consentIds, int visible, List<FinancialRecord> records) {
    }
}
