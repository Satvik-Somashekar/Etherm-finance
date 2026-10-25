package com.openfinance.ingestion;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory store of raw accounts, keyed by customer and linked account reference.
 * Re-ingesting the same account replaces the earlier copy, so fetches are idempotent.
 */
@Component
public class RawAccountStore {

    private final Map<String, Map<String, RawAccount>> byCustomer = new ConcurrentHashMap<>();

    public void putAll(Collection<RawAccount> accounts) {
        for (RawAccount a : accounts) {
            byCustomer.computeIfAbsent(a.customerId(), k -> new ConcurrentHashMap<>()).put(a.linkRefNumber(), a);
        }
    }

    public List<RawAccount> accounts(String customerId) {
        return byCustomer.getOrDefault(customerId, Map.of()).values().stream()
                .sorted((a, b) -> a.linkRefNumber().compareTo(b.linkRefNumber()))
                .toList();
    }

    public Set<String> customers() {
        return new TreeSet<>(byCustomer.keySet());
    }
}
