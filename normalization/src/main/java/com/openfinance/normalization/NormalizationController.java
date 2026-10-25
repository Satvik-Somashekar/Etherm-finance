package com.openfinance.normalization;

import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.NormalizedAccount;
import com.openfinance.core.normalize.NormalizationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;

import java.util.List;

@RestController
@RequestMapping("/api")
public class NormalizationController {

    private final NormalizationService service;

    public NormalizationController(NormalizationService service) {
        this.service = service;
    }

    @GetMapping("/consents/{consentId}/records")
    public List<FinancialRecord> records(@PathVariable String consentId) {
        return service.records(consentId);
    }

    @GetMapping("/consents/{consentId}/accounts")
    public List<NormalizedAccount> accounts(@PathVariable String consentId) {
        return service.accounts(consentId);
    }

    @GetMapping("/customers/{customerId}/accounts")
    public List<NormalizationService.AccountView> customerAccounts(@PathVariable String customerId) {
        return service.customerAccounts(customerId);
    }

    /** Passes ingestion's verdict through: a 403 for a revoked consent stays a 403. */
    @ExceptionHandler(HttpStatusCodeException.class)
    ProblemDetail upstream(HttpStatusCodeException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), "ingestion: " + e.getResponseBodyAsString());
    }

    @ExceptionHandler(NormalizationException.class)
    ProblemDetail invalidData(NormalizationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_ENTITY, e.getMessage());
    }
}
