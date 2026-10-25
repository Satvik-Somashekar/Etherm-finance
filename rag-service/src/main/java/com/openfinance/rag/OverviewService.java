package com.openfinance.rag;

import com.openfinance.core.compute.NetWorthHistory;
import com.openfinance.core.compute.RecordFilter;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Builds the Dashboard: net-worth history, recent activity and a few grounded insights. */
@Service
public class OverviewService {

    /** Asked on every Dashboard load; each one goes through the full Allow -> Find -> Add -> Phrase path. */
    static final List<String> INSIGHT_QUESTIONS = List.of(
            "How did my spending last month compare with the period before?",
            "Did my insurance premiums increase last quarter compared to the one before?",
            "How much did I invest in mutual funds this financial year?");

    static final int ACTIVITY_SIZE = 6;

    private final UpstreamClients upstream;
    private final QueryService queries;
    private final NetWorthHistory history = new NetWorthHistory();

    public OverviewService(UpstreamClients upstream, QueryService queries) {
        this.upstream = upstream;
        this.queries = queries;
    }

    public OverviewResponse overview(String customerId) {
        List<String> consentIds = queries.consents(new QueryRequest(null, customerId, null));

        List<FinancialRecord> assets = upstream.retrieve(consentIds, NetWorthHistory.filter()).records();
        List<OverviewResponse.HistoryPoint> points = history.compute(assets).stream()
                .map(p -> new OverviewResponse.HistoryPoint(p.month(), p.date(), p.value(), p.recordIds().size()))
                .toList();

        RecordFilter transactions = new RecordFilter(Set.of(), Set.of(RecordKind.TRANSACTION), Set.of(), null, null, null);
        List<OverviewResponse.Activity> activity = upstream.retrieve(consentIds, transactions).records().stream()
                .sorted(Comparator.comparing(FinancialRecord::date).thenComparing(FinancialRecord::recordId).reversed())
                .limit(ACTIVITY_SIZE)
                .map(r -> new OverviewResponse.Activity(r.recordId(), r.fipId(), r.fiType(), r.date(), r.amount(),
                        r.direction(), r.category(), r.description(), r.counterparty()))
                .toList();

        List<QueryResponse> insights = INSIGHT_QUESTIONS.stream()
                .map(q -> queries.answer(new QueryRequest(q, null, consentIds)))
                .filter(a -> !a.citations().isEmpty())
                .toList();

        LocalDate asOf = insights.isEmpty() ? null : insights.getFirst().grounding().asOf();
        return new OverviewResponse(customerId, consentIds, asOf, points, activity, insights);
    }
}
