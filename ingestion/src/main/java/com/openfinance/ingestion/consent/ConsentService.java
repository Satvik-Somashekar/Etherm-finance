package com.openfinance.ingestion.consent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentScope;
import com.openfinance.core.consent.ConsentStatus;
import com.openfinance.ingestion.RawAccount;
import com.openfinance.ingestion.RawAccountStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Simulates the AA consent manager and the FIU's FI fetch.
 *
 * <p>Lifecycle: created PENDING, approved to ACTIVE (which triggers the fetch), revoked to
 * REVOKED (which deletes the fetched data). A consent past its expiry reads as EXPIRED. Data is
 * only ever fetched for the consented FI types, providers and data range.
 */
@Service
public class ConsentService {

    private final RawAccountStore fipHoldings;
    private final Clock clock;
    private final Map<String, ConsentRecord> consents = new ConcurrentHashMap<>();
    private final Map<String, List<RawAccount>> fetched = new ConcurrentHashMap<>();

    public ConsentService(RawAccountStore fipHoldings, Clock clock) {
        this.fipHoldings = fipHoldings;
        this.clock = clock;
    }

    public ConsentArtifact create(CreateConsentRequest req) {
        return create(req, "CN-" + UUID.randomUUID().toString().substring(0, 13));
    }

    /** Creates a consent with a caller-chosen id; used for the seeded demo consents. */
    public ConsentArtifact create(CreateConsentRequest req, String id) {
        if (consents.containsKey(id)) {
            throw new IllegalStateException("consent " + id + " already exists");
        }
        if (req.customerId() == null || req.customerId().isBlank()) {
            throw new IllegalArgumentException("customerId is required");
        }
        if (req.dataFrom() == null || req.dataTo() == null) {
            throw new IllegalArgumentException("dataFrom and dataTo are required");
        }
        Instant now = clock.instant();
        int days = req.validForDays() == null ? 365 : req.validForDays();
        if (days <= 0) {
            throw new IllegalArgumentException("validForDays must be positive");
        }
        ConsentArtifact artifact = new ConsentArtifact(id, req.customerId(), ConsentStatus.PENDING,
                req.purposeCode() == null ? "101" : req.purposeCode(),
                req.purposeText() == null ? "Wealth management service" : req.purposeText(),
                req.fiTypes(), now, now.plus(Duration.ofDays(days)), req.dataFrom(), req.dataTo(),
                ConsentArtifact.FetchType.PERIODIC);
        consents.put(id, new ConsentRecord(artifact, req.fipIds() == null ? Set.of() : Set.copyOf(req.fipIds())));
        return artifact;
    }

    /** The customer approves in their AA app: the consent goes active and the FI fetch runs. */
    public FetchSummary approve(String consentId) {
        ConsentRecord rec = record(consentId);
        if (rec.artifact().status() != ConsentStatus.PENDING) {
            throw new IllegalStateException("only a PENDING consent can be approved; " + consentId + " is "
                    + current(rec).status());
        }
        ConsentRecord active = rec.withArtifact(rec.artifact().withStatus(ConsentStatus.ACTIVE));
        consents.put(consentId, active);
        return fetch(active);
    }

    /** Re-runs the FI fetch for an active consent (a periodic fetch). */
    public FetchSummary refresh(String consentId) {
        return fetch(record(consentId));
    }

    public ConsentArtifact revoke(String consentId) {
        ConsentRecord rec = record(consentId);
        ConsentArtifact revoked = rec.artifact().withStatus(ConsentStatus.REVOKED);
        consents.put(consentId, rec.withArtifact(revoked));
        fetched.remove(consentId);
        return revoked;
    }

    public ConsentArtifact get(String consentId) {
        return current(record(consentId));
    }

    public List<ConsentArtifact> forCustomer(String customerId) {
        return consents.values().stream()
                .filter(r -> r.artifact().customerId().equals(customerId))
                .map(this::current)
                .sorted(Comparator.comparing(ConsentArtifact::consentStart).thenComparing(ConsentArtifact::consentId))
                .toList();
    }

    /** Accounts fetched under the consent. Throws if the consent is no longer usable. */
    public List<RawAccount> fetchedAccounts(String consentId) {
        ConsentArtifact consent = get(consentId);
        ConsentScope.requireUsable(consent, clock.instant());
        return fetched.getOrDefault(consentId, List.of());
    }

    private FetchSummary fetch(ConsentRecord rec) {
        ConsentArtifact consent = current(rec);
        Instant now = clock.instant();
        ConsentScope.requireUsable(consent, now);
        List<RawAccount> out = new ArrayList<>();
        int transactions = 0;
        for (RawAccount held : fipHoldings.accounts(consent.customerId())) {
            if (!consent.fiTypes().contains(held.fiType())
                    || (!rec.fipIds().isEmpty() && !rec.fipIds().contains(held.fipId()))) {
                continue;
            }
            JsonNode trimmed = trimToDataRange(held.account(), consent.dataFrom(), consent.dataTo());
            transactions += trimmed.at("/transactions/transaction").size();
            out.add(held.fetchedUnder(consent.consentId(), now, trimmed));
        }
        fetched.put(consent.consentId(), List.copyOf(out));
        return new FetchSummary(consent.consentId(), out.size(), transactions, now);
    }

    /** A copy of the account holding only transactions inside the consented data range. */
    static JsonNode trimToDataRange(JsonNode account, LocalDate from, LocalDate to) {
        ObjectNode copy = account.deepCopy();
        JsonNode txns = copy.at("/transactions/transaction");
        if (txns.isArray()) {
            ArrayNode kept = copy.arrayNode();
            for (JsonNode t : txns) {
                LocalDate d = transactionDate(t);
                if (d != null && !d.isBefore(from) && !d.isAfter(to)) {
                    kept.add(t);
                }
            }
            ObjectNode tx = (ObjectNode) copy.get("transactions");
            tx.set("transaction", kept);
            tx.put("startDate", from.toString());
            tx.put("endDate", to.toString());
        }
        return copy;
    }

    private static LocalDate transactionDate(JsonNode t) {
        for (String field : List.of("transactionTimestamp", "transactionDate", "txnDate", "transactionDateTime")) {
            JsonNode v = t.get(field);
            if (v != null && v.asText().length() >= 10) {
                return LocalDate.parse(v.asText().substring(0, 10));
            }
        }
        return null;
    }

    private ConsentRecord record(String consentId) {
        ConsentRecord rec = consents.get(consentId);
        if (rec == null) {
            throw new ConsentNotFoundException(consentId);
        }
        return rec;
    }

    /** Status as of now: an ACTIVE consent past its expiry reads as EXPIRED. */
    private ConsentArtifact current(ConsentRecord rec) {
        ConsentArtifact a = rec.artifact();
        if (a.status() == ConsentStatus.ACTIVE && !clock.instant().isBefore(a.consentExpiry())) {
            return a.withStatus(ConsentStatus.EXPIRED);
        }
        return a;
    }

    public record FetchSummary(String consentId, int accounts, int transactions, Instant fetchedAt) {
    }
}
