package com.openfinance.retrieval;

import com.openfinance.core.compute.RecordFilter;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentScope;
import com.openfinance.core.model.FinancialRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The "Find" step. Structured retrieval over normalised records: for each consent it re-checks
 * the consent is usable right now, keeps only records that consent permits, then applies the
 * query's filter. Retrieval decides which records are seen; it never computes a figure.
 */
@Service
public class RetrievalService {

    private final UpstreamClients upstream;
    private final Clock clock;

    public RetrievalService(UpstreamClients upstream, Clock clock) {
        this.upstream = upstream;
        this.clock = clock;
    }

    public RetrievalResult retrieve(RetrievalRequest request) {
        if (request.consentIds() == null || request.consentIds().isEmpty()) {
            throw new IllegalArgumentException("at least one consentId is required");
        }
        RecordFilter filter = request.filter() == null
                ? new RecordFilter(null, null, null, null, null, null) : request.filter();

        // Two consents may cover the same account; keep one copy of each record.
        Map<String, FinancialRecord> visible = new LinkedHashMap<>();
        for (String consentId : request.consentIds()) {
            ConsentArtifact consent = upstream.consent(consentId);
            // Check before reading: a revoked consent must not cause any data to be fetched.
            ConsentScope.requireUsable(consent, clock.instant());
            for (FinancialRecord r : ConsentScope.apply(consent, upstream.records(consentId), clock.instant())) {
                visible.putIfAbsent(r.recordId(), r);
            }
        }
        List<FinancialRecord> matched = visible.values().stream()
                .filter(filter::matches)
                .sorted(Comparator.comparing(FinancialRecord::date).thenComparing(FinancialRecord::recordId))
                .toList();
        return new RetrievalResult(List.copyOf(request.consentIds()), visible.size(), matched);
    }
}
