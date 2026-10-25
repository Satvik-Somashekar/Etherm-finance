package com.openfinance.core.normalize;

import com.openfinance.core.model.FiType;

/** Maps one FI type's ReBIT account into the shared internal model. */
public interface AccountNormalizer {

    FiType fiType();

    NormalizedData normalize(SourceAccount source);
}
