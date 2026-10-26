package com.openfinance.rag;

import com.openfinance.core.consent.ConsentViolationException;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.HttpStatusCodeException;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService service;
    private final OverviewService overviews;
    private final ExportService exports;

    public QueryController(QueryService service, OverviewService overviews, ExportService exports) {
        this.service = service;
        this.overviews = overviews;
        this.exports = exports;
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return service.answer(request);
    }

    @GetMapping("/customers/{customerId}/overview")
    public OverviewResponse overview(@PathVariable String customerId) {
        return overviews.overview(customerId);
    }

    /** Consent-bounded transactions as CSV. Optional filters: from, to (yyyy-MM-dd), category, fiType. */
    @GetMapping("/customers/{customerId}/export/transactions.csv")
    public ResponseEntity<String> transactionsCsv(@PathVariable String customerId,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String fiType) {
        String csv = exports.transactionsCsv(customerId, date(from), date(to),
                category == null ? null : Category.valueOf(category.toUpperCase()),
                fiType == null ? null : FiType.valueOf(fiType.toUpperCase()));
        return csv(csv, "etherm-transactions.csv");
    }

    /** Monthly income, spending, savings and category spend as CSV, from the same compute layer as the Oracle. */
    @GetMapping("/customers/{customerId}/export/report.csv")
    public ResponseEntity<String> reportCsv(@PathVariable String customerId,
                                            @RequestParam(required = false) String from,
                                            @RequestParam(required = false) String to) {
        return csv(exports.reportCsv(customerId, date(from), date(to)), "etherm-report.csv");
    }

    private static ResponseEntity<String> csv(String body, String filename) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    private static LocalDate date(String value) {
        try {
            return value == null || value.isBlank() ? null : LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("dates must look like 2026-03-31: " + value);
        }
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
