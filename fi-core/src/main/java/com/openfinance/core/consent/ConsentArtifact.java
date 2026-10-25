package com.openfinance.core.consent;

import com.openfinance.core.model.FiType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Set;

/**
 * A simulated AA consent artifact: who granted it, for what purpose, which FI types, which
 * window of financial data, and how long the grant itself is valid.
 *
 * @param dataFrom first date of financial data the FIU may fetch (FIDataRange.from)
 * @param dataTo   last date of financial data the FIU may fetch (FIDataRange.to)
 */
public record ConsentArtifact(
        String consentId,
        String customerId,
        ConsentStatus status,
        String purposeCode,
        String purposeText,
        Set<FiType> fiTypes,
        Instant consentStart,
        Instant consentExpiry,
        LocalDate dataFrom,
        LocalDate dataTo,
        FetchType fetchType) {

    public enum FetchType {
        ONETIME,
        PERIODIC
    }

    public ConsentArtifact {
        Objects.requireNonNull(consentId, "consentId");
        Objects.requireNonNull(customerId, "customerId");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(consentStart, "consentStart");
        Objects.requireNonNull(consentExpiry, "consentExpiry");
        Objects.requireNonNull(dataFrom, "dataFrom");
        Objects.requireNonNull(dataTo, "dataTo");
        if (fiTypes == null || fiTypes.isEmpty()) {
            throw new IllegalArgumentException("a consent must name at least one FI type");
        }
        if (dataTo.isBefore(dataFrom)) {
            throw new IllegalArgumentException("dataTo is before dataFrom");
        }
        if (!consentExpiry.isAfter(consentStart)) {
            throw new IllegalArgumentException("consentExpiry must be after consentStart");
        }
        fiTypes = Set.copyOf(fiTypes);
    }

    public ConsentArtifact withStatus(ConsentStatus newStatus) {
        return new ConsentArtifact(consentId, customerId, newStatus, purposeCode, purposeText, fiTypes, consentStart,
                consentExpiry, dataFrom, dataTo, fetchType);
    }
}
