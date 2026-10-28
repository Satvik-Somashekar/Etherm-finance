package com.openfinance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Headers for an API that serves JSON and CSV only: nothing may be framed, sniffed as another type, cached or
 * run as a page. Exports keep their own Content-Disposition.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("X-Frame-Options", "DENY");
        response.setHeader("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Cross-Origin-Resource-Policy", "same-origin");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Permissions-Policy", "camera=(), microphone=(), geolocation=()");
        chain.doFilter(request, response);
    }
}
