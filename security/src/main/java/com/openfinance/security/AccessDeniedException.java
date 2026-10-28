package com.openfinance.security;

/** The caller is signed in but may not do this; answered with 403. */
public class AccessDeniedException extends RuntimeException {

    public AccessDeniedException(String message) {
        super(message);
    }
}
