package com.openfinance.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Every request needs a valid bearer token, except the public paths. Service-only paths also need a service
 * token. What a valid caller may then touch is decided by the controller through {@link Caller}.
 */
public class AuthFilter extends OncePerRequestFilter {

    private final TokenService tokens;
    private final SecurityProperties properties;
    private final AntPathMatcher paths = new AntPathMatcher();

    public AuthFilter(TokenService tokens, SecurityProperties properties) {
        this.tokens = tokens;
        this.properties = properties;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI().substring(request.getContextPath().length());
        if (matches(path, properties.publicPaths()) || "OPTIONS".equals(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }
        Optional<TokenService.Claims> claims = bearer(request).flatMap(tokens::verify);
        if (claims.isEmpty()) {
            Problems.write(response, 401, "Sign in first: this request needs a valid, unexpired token.");
            response.setHeader("WWW-Authenticate", "Bearer");
            return;
        }
        Caller caller = new Caller(claims.get().service()
                ? claims.get().subject().substring(TokenService.SERVICE_PREFIX.length()) : claims.get().subject(),
                claims.get().service());
        if (!caller.service() && matches(path, properties.servicePaths())) {
            Problems.write(response, 403, "Only Etherm services may call this.");
            return;
        }
        request.setAttribute(Caller.ATTRIBUTE, caller);
        chain.doFilter(request, response);
    }

    private boolean matches(String path, java.util.List<String> patterns) {
        return patterns.stream().anyMatch(p -> paths.match(p, path));
    }

    static Optional<String> bearer(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return Optional.of(header.substring(7).trim());
        }
        return Optional.empty();
    }
}
