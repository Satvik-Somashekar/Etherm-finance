package com.openfinance.core.model;

import java.util.Arrays;
import java.util.Optional;

/** The FI types Etherm accepts, keyed by the ReBIT {@code Account.type} value. */
public enum FiType {
    DEPOSIT("deposit"),
    MUTUAL_FUNDS("mutual_funds"),
    INSURANCE_POLICIES("insurance_policies"),
    EPF("epf"),
    EQUITIES("equities");

    private final String schemaType;

    FiType(String schemaType) {
        this.schemaType = schemaType;
    }

    public String schemaType() {
        return schemaType;
    }

    public static Optional<FiType> fromSchemaType(String value) {
        return Arrays.stream(values()).filter(t -> t.schemaType.equals(value)).findFirst();
    }
}
