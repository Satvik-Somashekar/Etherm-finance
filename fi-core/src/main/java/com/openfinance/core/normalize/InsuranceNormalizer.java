package com.openfinance.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.NormalizedAccount;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class InsuranceNormalizer implements AccountNormalizer {

    @Override
    public FiType fiType() {
        return FiType.INSURANCE_POLICIES;
    }

    @Override
    public NormalizedData normalize(SourceAccount source) {
        JsonNode account = source.account();
        JsonNode summary = account.path("summary");
        RecordFactory f = new RecordFactory(source, FiType.INSURANCE_POLICIES);
        List<FinancialRecord> records = new ArrayList<>();

        String policyName = Fields.text(summary, "policyName");
        String insurer = Fields.optText(summary, "insurer");
        String policyType = Fields.text(summary, "policyType");
        Map<String, String> policyAttrs = Map.of("policyNumber", Fields.text(summary, "policyNumber"),
                "policyType", policyType, "policyName", policyName);

        records.add(f.record("POLICY", RecordKind.POLICY, Category.POLICY_COVER, null,
                Fields.date(summary, "policyStartDate"), Fields.decimal(summary, "sumAssured"),
                policyName + " (" + policyType.toLowerCase() + " cover)", insurer,
                Map.of("policyNumber", policyAttrs.get("policyNumber"), "policyType", policyType,
                        "policyName", policyName, "premiumAmount", Fields.text(summary, "premiumAmount"),
                        "premiumFrequency", String.valueOf(Fields.optText(summary, "premiumFrequency")),
                        "maturityDate", String.valueOf(Fields.optText(summary, "maturityDate")))));

        for (JsonNode t : Fields.array(account, "/transactions/transaction")) {
            records.add(f.record(Fields.text(t, "txnId"), RecordKind.TRANSACTION, Category.PREMIUM_PAYMENT,
                    Direction.DEBIT, Fields.date(t, "txnDate"), Fields.decimal(t, "amount"),
                    "Premium - " + policyName, insurer, policyAttrs));
        }

        return new NormalizedData(new NormalizedAccount(source.linkRefNumber(), source.customerId(), source.consentId(),
                source.fipId(), FiType.INSURANCE_POLICIES, source.maskedAccNumber(), policyName, BigDecimal.ZERO,
                Fields.date(summary, "policyStartDate"), records.size()), records);
    }
}
