package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.QueryEngine;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.core.model.FinancialRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.function.Supplier;

/**
 * The read path: Allow (resolve the consents in force) -> Find (structured retrieval, scoped by
 * those consents) -> Add (the Java compute layer) -> Phrase (template or model, numbers by
 * placeholder only).
 */
@Service
public class QueryService {

    private final UpstreamClients upstream;
    private final QuestionParser parser = new QuestionParser();
    private final QueryEngine engine = new QueryEngine();
    private final AnswerPhraser phraser;
    private final Supplier<LocalDate> asOf;

    public QueryService(UpstreamClients upstream, AnswerPhraser phraser, Clock clock, PhrasingConfig.AsOf asOf) {
        this.upstream = upstream;
        this.phraser = phraser;
        this.asOf = asOf.date() != null ? asOf::date : () -> LocalDate.now(clock);
    }

    public QueryResponse answer(QueryRequest request) {
        if (request.question() == null || request.question().isBlank()) {
            throw new IllegalArgumentException("question is required");
        }
        // Allow
        List<String> consentIds = consents(request);
        LocalDate today = asOf.get();
        QueryPlan plan = parser.parse(request.question(), today);

        // Find
        UpstreamClients.Retrieved found = upstream.retrieve(consentIds, engine.retrievalFilter(plan));

        // Add
        ComputedAnswer computed = engine.compute(plan, found.records());

        // Phrase
        AnswerPhraser.Phrasing phrasing = phraser.phrase(request.question(), computed);

        return new QueryResponse(request.question(), phrasing.text(), phrasing.phrasedBy(), plan.intent(),
                plan.period() == null ? null : new QueryResponse.Period(plan.period().from(), plan.period().to(), plan.period().label()),
                computed.figures(), computed.breakdown(),
                computed.citations().stream().map(QueryService::cite).toList(),
                new QueryResponse.Grounding(consentIds, found.visible(), found.records().size(),
                        computed.citations().size(), today),
                export(plan, computed));
    }

    private static QueryResponse.Export export(QueryPlan plan, ComputedAnswer computed) {
        if (computed.empty()) {
            return null;
        }
        String kind = switch (plan.intent()) {
            case EXPORT_TRANSACTIONS -> "transactions";
            case EXPORT_REPORT -> "report";
            default -> null;
        };
        if (kind == null) {
            return null;
        }
        return new QueryResponse.Export(kind, plan.period() == null ? null : plan.period().from(),
                plan.period() == null ? null : plan.period().to(),
                plan.categories().stream().map(Enum::name).sorted().toList());
    }

    List<String> consents(QueryRequest request) {
        if (request.consentIds() != null && !request.consentIds().isEmpty()) {
            return request.consentIds();
        }
        if (request.customerId() == null || request.customerId().isBlank()) {
            throw new IllegalArgumentException("customerId or consentIds is required");
        }
        List<String> active = upstream.consents(request.customerId()).stream()
                .filter(c -> c.status() == ConsentStatus.ACTIVE)
                .map(ConsentArtifact::consentId)
                .toList();
        if (active.isEmpty()) {
            throw new NoActiveConsentException(request.customerId());
        }
        return active;
    }

    private static QueryResponse.Citation cite(FinancialRecord r) {
        return new QueryResponse.Citation(r.recordId(), r.accountId(), r.fipId(), r.fiType(), r.date(), r.amount(),
                r.description(), r.counterparty());
    }
}
