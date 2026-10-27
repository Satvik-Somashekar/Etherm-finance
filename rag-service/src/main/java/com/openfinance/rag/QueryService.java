package com.openfinance.rag;

import com.openfinance.core.compute.ComputedAnswer;
import com.openfinance.core.compute.DateRange;
import com.openfinance.core.compute.Intent;
import com.openfinance.core.compute.QueryEngine;
import com.openfinance.core.compute.QueryPlan;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
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
        QueryPlan plan = withConversation(parser.parse(request.question(), today), request.history(), today);

        // Scope: a Knowledge Base section only removes data from what the consents allow.
        Set<FiType> scope = request.scope() == null ? Set.of() : request.scope();
        if (!scope.isEmpty()) {
            Set<FiType> usable = new java.util.HashSet<>(naturalTypes(plan));
            usable.retainAll(scope);
            if (usable.isEmpty()) {
                return outsideScope(request.question(), plan, scope, consentIds, today);
            }
            plan = narrow(plan, usable);
        }

        // Find
        UpstreamClients.Retrieved found = upstream.retrieve(consentIds, engine.retrievalFilter(plan));

        // Add
        ComputedAnswer computed = engine.compute(plan, found.records());

        // Phrase
        AnswerPhraser.Phrasing phrasing = phraser.phrase(request.question(), computed);
        String text = plan.intent() == Intent.ADVICE && !computed.empty()
                ? phrasing.text() + " " + ADVICE_NOTE : phrasing.text();

        return new QueryResponse(request.question(), text, phrasing.phrasedBy(), plan.intent(),
                plan.period() == null ? null : new QueryResponse.Period(plan.period().from(), plan.period().to(), plan.period().label()),
                computed.figures(), computed.breakdown(),
                computed.citations().stream().map(QueryService::cite).toList(),
                new QueryResponse.Grounding(consentIds, found.visible(), found.records().size(),
                        computed.citations().size(), today),
                export(plan, computed));
    }

    static final String ADVICE_NOTE = "This is a suggestion based on your own numbers, not financial advice.";

    /**
     * A suggestion needs a topic and a period. A follow-up such as "how can I reduce it?" borrows both from the
     * most recent spending question in the conversation; with none, the last three full months are used.
     */
    private QueryPlan withConversation(QueryPlan plan, List<String> history, LocalDate today) {
        if (plan.intent() != Intent.ADVICE) {
            return plan;
        }
        if (history != null) {
            for (int i = history.size() - 1; i >= 0 && (plan.categories().isEmpty() || plan.period() == null); i--) {
                QueryPlan earlier = parser.parse(history.get(i), today);
                if (!CONTEXT_INTENTS.contains(earlier.intent())) {
                    continue;
                }
                if (plan.categories().isEmpty() && !earlier.categories().isEmpty()) {
                    plan = plan.withCategories(earlier.categories());
                }
                if (plan.period() == null && earlier.period() != null) {
                    plan = plan.withPeriod(earlier.period());
                }
                break;
            }
        }
        if (plan.period() == null) {
            LocalDate first = today.withDayOfMonth(1).minusMonths(3);
            plan = plan.withPeriod(new DateRange(first, today.withDayOfMonth(1).minusDays(1), "the last 3 months"));
        }
        return plan;
    }

    private static final Set<Intent> CONTEXT_INTENTS = EnumSet.of(Intent.SPEND_TOTAL, Intent.SPEND_CATEGORY,
            Intent.TOP_EXPENSES, Intent.SPEND_TREND, Intent.TOP_MERCHANTS, Intent.SAVINGS_RATE, Intent.ADVICE);

    /** The FI types an intent reads from; all of them when it does not pick. */
    private Set<FiType> naturalTypes(QueryPlan plan) {
        Set<FiType> types = engine.retrievalFilter(plan).fiTypes();
        return types.isEmpty() ? EnumSet.allOf(FiType.class) : types;
    }

    /** Intents that read "everything" are limited to the section; the others already name their types. */
    private static QueryPlan narrow(QueryPlan plan, Set<FiType> usable) {
        return switch (plan.intent()) {
            case BALANCE, HOLDINGS, EXPORT_TRANSACTIONS -> plan.withFiTypes(usable);
            default -> plan;
        };
    }

    private QueryResponse outsideScope(String question, QueryPlan plan, Set<FiType> scope, List<String> consentIds,
                                       LocalDate today) {
        String section = scope.stream().map(QueryService::sectionName).sorted().collect(Collectors.joining(" and "));
        String text = "That is outside the section you selected (" + section + "), so I did not look at it. "
                + "Switch to All accounts or the matching section to ask it.";
        return new QueryResponse(question, text, "template", plan.intent(),
                plan.period() == null ? null : new QueryResponse.Period(plan.period().from(), plan.period().to(), plan.period().label()),
                List.of(), List.of(), List.of(), new QueryResponse.Grounding(consentIds, 0, 0, 0, today), null);
    }

    private static String sectionName(FiType type) {
        return switch (type) {
            case DEPOSIT -> "Deposits";
            case MUTUAL_FUNDS -> "Mutual funds";
            case INSURANCE_POLICIES -> "Insurance";
            case EPF -> "EPF";
            case EQUITIES -> "Stocks";
        };
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
                plan.categories().stream().map(Enum::name).sorted().toList(),
                plan.fiTypes().stream().map(Enum::name).sorted().toList());
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
