package com.openfinance.ingestion.consent;

import com.openfinance.security.Caller;
import com.openfinance.core.consent.ConsentArtifact;
import com.openfinance.core.consent.ConsentViolationException;
import com.openfinance.ingestion.RawAccount;
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

@RestController
@RequestMapping("/api/consents")
public class ConsentController {

    private final ConsentService consents;

    public ConsentController(ConsentService consents) {
        this.consents = consents;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ConsentArtifact create(@RequestBody CreateConsentRequest request) {
        Caller.current().requireCustomer(request.customerId());
        return consents.create(request);
    }

    @PostMapping("/{consentId}/approve")
    public ConsentService.FetchSummary approve(@PathVariable String consentId) {
        owned(consentId);
        return consents.approve(consentId);
    }

    @PostMapping("/{consentId}/refresh")
    public ConsentService.FetchSummary refresh(@PathVariable String consentId) {
        owned(consentId);
        return consents.refresh(consentId);
    }

    @PostMapping("/{consentId}/revoke")
    public ConsentArtifact revoke(@PathVariable String consentId) {
        owned(consentId);
        return consents.revoke(consentId);
    }

    @GetMapping("/{consentId}")
    public ConsentArtifact get(@PathVariable String consentId) {
        return owned(consentId);
    }

    @GetMapping
    public List<ConsentArtifact> list(@RequestParam String customerId) {
        Caller.current().requireCustomer(customerId);
        return consents.forCustomer(customerId);
    }

    /** The FI data fetched under this consent. Refused once the consent is not active. */
    @GetMapping("/{consentId}/accounts")
    public List<RawAccount> accounts(@PathVariable String consentId) {
        owned(consentId);
        return consents.fetchedAccounts(consentId);
    }

    /** The consent, if it belongs to the signed-in customer (or the caller is another Etherm service). */
    private ConsentArtifact owned(String consentId) {
        ConsentArtifact consent = consents.get(consentId);
        Caller.current().requireCustomer(consent.customerId());
        return consent;
    }

    @ExceptionHandler(ConsentNotFoundException.class)
    ProblemDetail notFound(ConsentNotFoundException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, e.getMessage());
    }

    @ExceptionHandler(ConsentViolationException.class)
    ProblemDetail forbidden(ConsentViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler({IllegalArgumentException.class, IllegalStateException.class})
    ProblemDetail badRequest(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }
}
