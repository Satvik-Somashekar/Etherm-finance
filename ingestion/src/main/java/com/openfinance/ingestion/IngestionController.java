package com.openfinance.ingestion;

import com.openfinance.security.Caller;
import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.FiType;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class IngestionController {

    private final IngestionService ingestion;
    private final RawAccountStore store;

    public IngestionController(IngestionService ingestion, RawAccountStore store) {
        this.ingestion = ingestion;
        this.store = store;
    }

    @PostMapping("/fi/fetch-responses")
    @ResponseStatus(HttpStatus.CREATED)
    public IngestionService.IngestionSummary ingest(@RequestBody JsonNode payload) {
        return ingestion.ingest(payload);
    }

    /** Customers with data at the simulated FIPs. Their data itself is only reachable through a consent. */
    @GetMapping("/customers")
    public Set<String> customers() {
        return store.customers();
    }

    /**
     * Account discovery: which accounts the customer holds at each FIP, by masked number only. This
     * is what an AA shows before consent; balances and transactions need an approved consent.
     */
    @GetMapping("/customers/{customerId}/fips")
    public List<DiscoveredAccount> discover(@PathVariable String customerId) {
        Caller.current().requireCustomer(customerId);
        return store.accounts(customerId).stream()
                .map(a -> new DiscoveredAccount(a.fipId(), a.fiType(), a.linkRefNumber(), a.maskedAccNumber()))
                .toList();
    }

    public record DiscoveredAccount(String fipId, FiType fiType, String linkRefNumber, String maskedAccNumber) {
    }

    @ExceptionHandler(InvalidFiPayloadException.class)
    ProblemDetail invalidPayload(InvalidFiPayloadException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "FI payload failed validation");
        pd.setProperty("problems", e.problems());
        return pd;
    }
}
