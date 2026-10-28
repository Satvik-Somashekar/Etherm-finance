package com.openfinance.security;

import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

/** Writes an RFC 9457 problem body from a filter, before any controller runs. */
final class Problems {

    private Problems() {
    }

    static void write(HttpServletResponse response, int status, String detail) throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding("UTF-8");
        String title = switch (status) {
            case 401 -> "Unauthorized";
            case 403 -> "Forbidden";
            case 413 -> "Content Too Large";
            case 429 -> "Too Many Requests";
            default -> "Error";
        };
        response.getWriter().write("{\"type\":\"about:blank\",\"title\":\"" + title + "\",\"status\":" + status
                + ",\"detail\":\"" + detail.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}");
    }
}
