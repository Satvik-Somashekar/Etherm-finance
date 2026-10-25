package com.openfinance.core.consent;

import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Decides what data a consent allows to be touched. This is deliberately separate from the
 * compute layer: consent scoping decides which records are visible, never what a number is.
 */
public final class ConsentScope {

    private ConsentScope() {
    }

    /** Throws unless the consent is active and inside its validity window at {@code now}. */
    public static void requireUsable(ConsentArtifact consent, Instant now) {
        if (consent.status() != ConsentStatus.ACTIVE) {
            throw new ConsentViolationException("consent " + consent.consentId() + " is " + consent.status());
        }
        if (now.isBefore(consent.consentStart())) {
            throw new ConsentViolationException("consent " + consent.consentId() + " is not valid until "
                    + consent.consentStart());
        }
        if (!now.isBefore(consent.consentExpiry())) {
            throw new ConsentViolationException("consent " + consent.consentId() + " expired at "
                    + consent.consentExpiry());
        }
    }

    /**
     * Whether one record falls inside the consent: same customer, a consented FI type, and for
     * transactions a date inside the consented data range. Balances, holdings and policy terms are
     * point-in-time facts reported with the fetch, so only the FI type check applies to them.
     */
    public static boolean permits(ConsentArtifact consent, FinancialRecord record) {
        if (!consent.customerId().equals(record.customerId()) || !consent.fiTypes().contains(record.fiType())) {
            return false;
        }
        return record.kind() != RecordKind.TRANSACTION || inDataRange(consent, record.date());
    }

    public static boolean inDataRange(ConsentArtifact consent, LocalDate date) {
        return !date.isBefore(consent.dataFrom()) && !date.isAfter(consent.dataTo());
    }

    /** Checks the consent is usable, then keeps only the records it permits. */
    public static List<FinancialRecord> apply(ConsentArtifact consent, List<FinancialRecord> records, Instant now) {
        requireUsable(consent, now);
        return records.stream().filter(r -> permits(consent, r)).toList();
    }
}
