package com.openfinance.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.Collection;

/**
 * Who is making the current request, as proven by its token. Controllers call {@link #requireCustomer} with the
 * customer a request is about; a customer may only act on themself, and another Etherm service may act for anyone
 * (it has already checked the customer at the edge).
 */
public record Caller(String subject, boolean service) {

    static final String ATTRIBUTE = Caller.class.getName();

    /** The caller of the request being handled. Every request past the filter has one. */
    public static Caller current() {
        if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
            HttpServletRequest request = attrs.getRequest();
            if (request.getAttribute(ATTRIBUTE) instanceof Caller caller) {
                return caller;
            }
        }
        throw new AccessDeniedException("not signed in");
    }

    /** The customer id this caller acts as; for a service, the one the request names. */
    public String customerOr(String named) {
        return service ? named : subject;
    }

    /** Refuses unless the caller is that customer or a service. */
    public void requireCustomer(String customerId) {
        if (!service && !subject.equals(customerId)) {
            throw new AccessDeniedException("this session cannot read another customer's data");
        }
    }

    /** Refuses unless the caller is a service. */
    public void requireService() {
        if (!service) {
            throw new AccessDeniedException("only Etherm services may call this");
        }
    }

    /** Refuses unless every id is in {@code owned}, the ids the caller's customer holds. */
    public void requireOwned(Collection<String> ids, Collection<String> owned) {
        if (!service && !owned.containsAll(ids)) {
            throw new AccessDeniedException("this session cannot use another customer's consent");
        }
    }
}
