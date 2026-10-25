package com.openfinance.ingestion.consent;

import com.openfinance.core.consent.ConsentArtifact;

import java.util.Set;

/** A consent artifact plus the FIP restriction the simulated consent manager keeps beside it. */
record ConsentRecord(ConsentArtifact artifact, Set<String> fipIds) {

    ConsentRecord withArtifact(ConsentArtifact a) {
        return new ConsentRecord(a, fipIds);
    }
}
