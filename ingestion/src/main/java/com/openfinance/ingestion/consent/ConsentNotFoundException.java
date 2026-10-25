package com.openfinance.ingestion.consent;

public class ConsentNotFoundException extends RuntimeException {

    public ConsentNotFoundException(String consentId) {
        super("no consent " + consentId);
    }
}
