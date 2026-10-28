package com.openfinance.rag;

import com.openfinance.security.Caller;
import com.openfinance.security.UpstreamProblems;
import com.openfinance.core.consent.ConsentArtifact;
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
import java.util.List;
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
    private final UpstreamClients upstream;

    /** Longest question, and most and longest earlier turns, a request may carry. */
    static final int MAX_QUESTION = 1000;
    static final int MAX_TURNS = 12;
    static final int MAX_TURN = 4000;

    public QueryController(QueryService service, OverviewService overviews, ExportService exports, ChatService chat,
                           TurnSigner signer, UpstreamClients upstream) {
        this.chat = chat;
        this.signer = signer;
        this.upstream = upstream;
        this.service = service;
        this.overviews = overviews;
        this.exports = exports;
    }

    @PostMapping("/query")
    public QueryResponse query(@RequestBody QueryRequest body) {
        checkSize(body.question(), body.history() == null ? 0 : body.history().size(),
                body.history() == null ? List.of() : body.history());
        String customer = authorise(body.customerId(), body.consentIds());
        QueryRequest request = new QueryRequest(body.question(), customer, body.consentIds(), body.scope(), body.history());
        return signed(service.answer(request), TurnSigner.principal(request.customerId(), request.consentIds()));
    }

    /** Free conversation: the model looks figures up through tools when one is configured, else the typed-question path answers. */
    @PostMapping("/chat")
    public QueryResponse chat(@RequestBody ChatRequest body) {
        List<ChatRequest.Turn> turns = body.turns() == null ? List.of() : body.turns();
        checkSize(body.question(), turns.size(), turns.stream().map(ChatRequest.Turn::text).toList());
        String customer = authorise(body.customerId(), body.consentIds());
        ChatRequest request = new ChatRequest(body.question(), customer, body.consentIds(), body.scope(), body.turns());
        return signed(chat.chat(request), TurnSigner.principal(request.customerId(), request.consentIds()));
    }

    /**
     * The customer a question is about. A signed-in customer may only name themself, and may only name consents
     * they hold; another Etherm service may name anyone. Returns the customer id to answer for.
     */
    private String authorise(String customerId, List<String> consentIds) {
        Caller caller = Caller.current();
        if (customerId != null && !customerId.isBlank()) {
            caller.requireCustomer(customerId);
        }
        String customer = caller.customerOr(customerId);
        if (!caller.service() && consentIds != null && !consentIds.isEmpty()) {
            caller.requireOwned(consentIds, upstream.consents(customer).stream().map(ConsentArtifact::consentId).toList());
        }
        return customer;
    }

    private static void checkSize(String question, int turns, List<String> texts) {
        if (question != null && question.length() > MAX_QUESTION) {
            throw new IllegalArgumentException("questions are limited to " + MAX_QUESTION + " characters");
        }
        if (turns > MAX_TURNS || texts.stream().anyMatch(t -> t != null && t.length() > MAX_TURN)) {
            throw new IllegalArgumentException("a request may carry at most " + MAX_TURNS + " earlier turns of "
                    + MAX_TURN + " characters each");
        }
    }

    private QueryResponse signed(QueryResponse response, String principal) {
        return response.withTurnSignature(signer.sign(principal, response.answer()));
    }

    @GetMapping("/customers/{customerId}/overview")
    public OverviewResponse overview(@PathVariable String customerId) {
        Caller.current().requireCustomer(customerId);
        return overviews.overview(customerId);
    }

    /** Consent-bounded transactions as CSV. Optional filters: from, to (yyyy-MM-dd), category and fiType (comma-separated). */
    @GetMapping("/customers/{customerId}/export/transactions.csv")
    public ResponseEntity<String> transactionsCsv(@PathVariable String customerId,
                                                  @RequestParam(required = false) String from,
                                                  @RequestParam(required = false) String to,
                                                  @RequestParam(required = false) String category,
                                                  @RequestParam(required = false) String fiType) {
        Caller.current().requireCustomer(customerId);
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
        Caller.current().requireCustomer(customerId);
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
        return ProblemDetail.forStatusAndDetail(e.getStatusCode(), "upstream: " + UpstreamProblems.upstreamDetail(e));
    }
}
