package com.openfinance.rag;

public class NoActiveConsentException extends RuntimeException {

    public NoActiveConsentException(String customerId) {
        super("customer " + customerId + " has no active consent");
    }
}
