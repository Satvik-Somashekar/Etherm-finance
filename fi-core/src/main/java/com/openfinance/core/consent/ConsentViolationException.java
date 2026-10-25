package com.openfinance.core.consent;

/** Raised when data is requested under a consent that does not allow it. */
public class ConsentViolationException extends RuntimeException {

    public ConsentViolationException(String message) {
        super(message);
    }
}
