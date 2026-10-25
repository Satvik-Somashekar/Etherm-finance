package com.openfinance.rag;

import com.openfinance.core.consent.ConsentViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService service;
    private final OverviewService overviews;

    public QueryController(QueryService service, OverviewService overviews) {
        this.service = service;
        this.overviews = overviews;
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return service.answer(request);
    }

    @GetMapping("/customers/{customerId}/overview")
    public OverviewResponse overview(@PathVariable String customerId) {
        return overviews.overview(customerId);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler({NoActiveConsentException.class, ConsentViolationException.class})
    ProblemDetail forbidden(RuntimeException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    ProblemDetail upstream(HttpStatusCodeException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), "upstream: " + e.getResponseBodyAsString());
    }
}
