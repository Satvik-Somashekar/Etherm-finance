package com.openfinance.ingestion;

import java.util.List;

public class InvalidFiPayloadException extends RuntimeException {

    private final List<String> problems;

    public InvalidFiPayloadException(List<String> problems) {
        super("Invalid FI payload: " + String.join("; ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
