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
import java.util.EnumSet;
import java.util.Set;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api")
public class QueryController {

    private final QueryService service;
    private final OverviewService overviews;
    private final ExportService exports;
    private final ChatService chat;
    private final TurnSigner signer;

    public QueryController(QueryService service, OverviewService overviews, ExportService exports, ChatService chat,
                           TurnSigner signer) {
        this.chat = chat;
        this.signer = signer;
        this.service = service;
        this.overviews = overviews;
        this.exports = exports;
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest request) {
        return signed(service.answer(request), TurnSigner.principal(request.customerId(), request.consentIds()));
    }

    /** Free conversation: the model looks figures up through tools when one is configured, else the typed-question path answers. */
    @PostMapping("/chat")
    public QueryResponse chat(@RequestBody ChatRequest request) {
        return signed(chat.chat(request), TurnSigner.principal(request.customerId(), request.consentIds()));
    }

    private QueryResponse signed(QueryResponse response, String principal) {
        return response.withTurnSignature(signer.sign(principal, response.answer()));
    }

    @GetMapping("/customers/{customerId}/overview")
    public OverviewResponse overview(@PathVariable String customerId) {
        return overviews.overview(customerId);
    }

    /** Consent-bounded transactions as CSV. Optional filters: from, to (yyyy-MM-dd), category and fiType (comma-separated). */
    @GetMapping("/customers/{customerId}/export/transactions.csv")
    public ResponseEntity<String> transactionsCsv(@PathVariable String customerId,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String fiType) {
        Set<Category> categories = EnumSet.noneOf(Category.class);
        if (category != null && !category.isBlank()) {
            for (String name : category.split(",")) {
                categories.add(Category.valueOf(name.trim().toUpperCase()));
            }
        }
        Set<FiType> fiTypes = EnumSet.noneOf(FiType.class);
        if (fiType != null && !fiType.isBlank()) {
            for (String name : fiType.split(",")) {
                fiTypes.add(FiType.valueOf(name.trim().toUpperCase()));
            }
        }
        String csv = exports.transactionsCsv(customerId, date(from), date(to), categories, fiTypes);
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
