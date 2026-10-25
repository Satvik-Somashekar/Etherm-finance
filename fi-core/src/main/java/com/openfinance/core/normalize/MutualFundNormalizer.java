package com.openfinance.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.NormalizedAccount;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MutualFundNormalizer implements AccountNormalizer {

    @Override
    public FiType fiType() {
        return FiType.MUTUAL_FUNDS;
    }

    @Override
    public NormalizedData normalize(SourceAccount source) {
        JsonNode account = source.account();
        RecordFactory f = new RecordFactory(source, FiType.MUTUAL_FUNDS);
        List<FinancialRecord> records = new ArrayList<>();
        Map<String, String> schemeNames = new HashMap<>();

        LocalDate asOf = null;
        for (JsonNode h : Fields.array(account, "/summary/investment/holdings/holding")) {
            String isin = Fields.text(h, "isin");
            String name = Fields.text(h, "isinDescription");
            schemeNames.put(isin, name);
            BigDecimal units = Fields.decimal(h, "closingUnits");
            BigDecimal nav = Fields.decimal(h, "nav");
            LocalDate navDate = Fields.date(h, "navDate");
            asOf = asOf == null || navDate.isAfter(asOf) ? navDate : asOf;
            records.add(f.record("H:" + isin, RecordKind.HOLDING, Category.HOLDING_VALUE, null, navDate,
                    units.multiply(nav).setScale(2, RoundingMode.HALF_UP), name, Fields.text(h, "amc"),
                    Map.of("isin", isin, "units", units.toPlainString(), "nav", nav.toPlainString(),
                            "folioNo", String.valueOf(Fields.optText(h, "folioNo")),
                            "schemeCategory", String.valueOf(Fields.optText(h, "schemeCategory")))));
        }

        for (JsonNode t : Fields.array(account, "/transactions/transaction")) {
            boolean buy = "BUY".equals(Fields.text(t, "type"));
            String isin = Fields.text(t, "isin");
            records.add(f.record(Fields.text(t, "txnId"), RecordKind.TRANSACTION,
                    buy ? Category.MF_PURCHASE : Category.MF_REDEMPTION, buy ? Direction.DEBIT : Direction.CREDIT,
                    Fields.date(t, "transactionDate"), Fields.decimal(t, "amount"),
                    schemeNames.getOrDefault(isin, Fields.text(t, "narration")), Fields.text(t, "amc"),
                    Map.of("isin", isin, "units", String.valueOf(Fields.optText(t, "units")),
                            "nav", String.valueOf(Fields.optText(t, "nav")))));
        }

        BigDecimal value = Fields.decimal(account.path("summary"), "currentValue");
        LocalDate valueDate = asOf == null ? LocalDate.parse(Fields.text(account.path("transactions"), "endDate")) : asOf;
        records.add(f.record("SUMMARY", RecordKind.BALANCE, Category.ACCOUNT_BALANCE, null, valueDate, value,
                "Mutual fund folio value", source.fipId(),
                Map.of("costValue", Fields.text(account.path("summary"), "costValue"))));

        String label = "Mutual funds " + source.maskedAccNumber();
        return new NormalizedData(new NormalizedAccount(source.linkRefNumber(), source.customerId(), source.consentId(),
                source.fipId(), FiType.MUTUAL_FUNDS, source.maskedAccNumber(), label, value, valueDate, records.size()),
                records);
    }
}
