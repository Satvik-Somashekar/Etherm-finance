package com.openfinance.ingestion;

import com.openfinance.core.model.FiType;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class IngestionService {

    private final FiPayloadParser parser;
    private final RawAccountStore store;

    public IngestionService(FiPayloadParser parser, RawAccountStore store) {
        this.parser = parser;
        this.store = store;
    }

    public IngestionSummary ingest(JsonNode payload) {
        List<RawAccount> accounts = parser.parse(payload);
        store.putAll(accounts);
        Map<FiType, Integer> perType = new EnumMap<>(FiType.class);
        int transactions = 0;
        for (RawAccount a : accounts) {
            perType.merge(a.fiType(), 1, Integer::sum);
            transactions += a.account().at("/transactions/transaction").size();
        }
        return new IngestionSummary(accounts.get(0).customerId(), accounts.size(), transactions, perType);
    }

    public record IngestionSummary(String customerId, int accounts, int transactions, Map<FiType, Integer> accountsByType) {
    }
}
