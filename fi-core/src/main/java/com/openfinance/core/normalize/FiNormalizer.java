package com.openfinance.core.normalize;

import com.openfinance.core.model.FiType;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Entry point for normalisation: picks the FI type's normaliser from the account's own
 * {@code type} field. Adding an FI type means adding one {@link AccountNormalizer}.
 */
public class FiNormalizer {

    private final Map<FiType, AccountNormalizer> byType = new EnumMap<>(FiType.class);

    public FiNormalizer() {
        this(List.of(new DepositNormalizer(), new MutualFundNormalizer(), new InsuranceNormalizer(),
                new EpfNormalizer(), new EquityNormalizer()));
    }

    public FiNormalizer(List<AccountNormalizer> normalizers) {
        normalizers.forEach(n -> byType.put(n.fiType(), n));
    }

    public NormalizedData normalize(SourceAccount source) {
        String type = source.account().path("type").asText();
        FiType fiType = FiType.fromSchemaType(type)
                .orElseThrow(() -> new NormalizationException("unsupported account type '" + type + "'"));
        AccountNormalizer normalizer = byType.get(fiType);
        if (normalizer == null) {
            throw new NormalizationException("no normaliser registered for " + fiType);
        }
        try {
            return normalizer.normalize(source);
        } catch (NormalizationException e) {
            throw new NormalizationException(source.linkRefNumber() + ": " + e.getMessage());
        }
    }
}
