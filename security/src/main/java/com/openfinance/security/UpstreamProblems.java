package com.openfinance.security;

import org.springframework.http.ProblemDetail;
import org.springframework.web.client.HttpStatusCodeException;

/** What of another service's error may be shown to a caller: its problem detail, never its raw body. */
public final class UpstreamProblems {

    private UpstreamProblems() {
    }

    public static String upstreamDetail(HttpStatusCodeException e) {
        try {
            ProblemDetail problem = e.getResponseBodyAs(ProblemDetail.class);
            if (problem != null && problem.getDetail() != null) {
                String detail = problem.getDetail();
                return detail.length() <= 300 ? detail : detail.substring(0, 300);
            }
        } catch (RuntimeException ignored) {
            // Not a problem body; fall through to the status alone.
        }
        return e.getStatusText().isBlank() ? "HTTP " + e.getStatusCode().value() : e.getStatusText();
    }
}
