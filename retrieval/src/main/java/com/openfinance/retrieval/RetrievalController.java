package com.openfinance.retrieval;

import com.openfinance.security.UpstreamProblems;
import com.openfinance.core.consent.ConsentViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;

@RestController
@RequestMapping("/api")
public class RetrievalController {

    private final RetrievalService service;

    public RetrievalController(RetrievalService service) {
        this.service = service;
    }

    @PostMapping("/retrieve")
    public RetrievalResult retrieve(@RequestBody RetrievalRequest request) {
        return service.retrieve(request);
    }

    @ExceptionHandler(ConsentViolationException.class)
    ProblemDetail consent(ConsentViolationException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, e.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException e) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, e.getMessage());
    }

    @ExceptionHandler(HttpStatusCodeException.class)
    ProblemDetail upstream(HttpStatusCodeException e) {
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), "upstream: " + UpstreamProblems.upstreamDetail(e));
    }
}
