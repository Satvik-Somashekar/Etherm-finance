package com.openfinance.ingestion;

import com.openfinance.core.model.FiType;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
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

    @GetMapping("/customers")
    public Set<String> customers() {
        return store.customers();
    }

    @GetMapping("/customers/{customerId}/accounts")
    public List<RawAccount> accounts(@PathVariable String customerId,
                                     @RequestParam(required = false) FiType fiType) {
        return store.accounts(customerId).stream()
                .filter(a -> fiType == null || a.fiType() == fiType)
                .toList();
    }

    @ExceptionHandler(InvalidFiPayloadException.class)
    ProblemDetail invalidPayload(InvalidFiPayloadException e) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, "FI payload failed validation");
        pd.setProperty("problems", e.problems());
        return pd;
    }
}
