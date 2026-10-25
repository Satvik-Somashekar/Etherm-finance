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
import java.util.List;
import java.util.Map;

public class EquityNormalizer implements AccountNormalizer {

    @Override
    public FiType fiType() {
        return FiType.EQUITIES;
    }

    @Override
    public NormalizedData normalize(SourceAccount source) {
        JsonNode account = source.account();
        RecordFactory f = new RecordFactory(source, FiType.EQUITIES);
        List<FinancialRecord> records = new ArrayList<>();
        LocalDate asOf = LocalDate.parse(Fields.text(account.path("transactions"), "endDate"));

        for (JsonNode h : Fields.array(account, "/summary/investment/holdings/holding")) {
            BigDecimal units = Fields.decimal(h, "units");
            BigDecimal ltp = Fields.decimal(h, "lastTradedPrice");
            String issuer = Fields.text(h, "issuerName");
            records.add(f.record("H:" + Fields.text(h, "isin"), RecordKind.HOLDING, Category.HOLDING_VALUE, null, asOf,
                    units.multiply(ltp).setScale(2, RoundingMode.HALF_UP), issuer, issuer,
                    Map.of("isin", Fields.text(h, "isin"), "units", units.toPlainString(),
                            "lastTradedPrice", ltp.toPlainString())));
        }

        for (JsonNode t : Fields.array(account, "/transactions/transaction")) {
            boolean buy = "BUY".equals(Fields.text(t, "type"));
            BigDecimal value = t.hasNonNull("tradeValue") ? Fields.decimal(t, "tradeValue")
                    : Fields.decimal(t, "rate").multiply(Fields.decimal(t, "units"));
            String company = Fields.text(t, "companyName");
            records.add(f.record(Fields.text(t, "txnId"), RecordKind.TRANSACTION,
                    buy ? Category.EQUITY_BUY : Category.EQUITY_SELL, buy ? Direction.DEBIT : Direction.CREDIT,
                    Fields.date(t, "transactionDateTime"), value, Fields.text(t, "narration"), company,
                    Map.of("isin", Fields.text(t, "isin"), "units", Fields.text(t, "units"),
                            "rate", Fields.text(t, "rate"), "exchange", String.valueOf(Fields.optText(t, "exchange")))));
        }

        BigDecimal value = Fields.decimal(account.path("summary"), "currentValue");
        records.add(f.record("SUMMARY", RecordKind.BALANCE, Category.ACCOUNT_BALANCE, null, asOf, value,
                "Demat portfolio value", source.fipId(), Map.of()));

        return new NormalizedData(new NormalizedAccount(source.linkRefNumber(), source.customerId(), source.consentId(),
                source.fipId(), FiType.EQUITIES, source.maskedAccNumber(), "Equities " + source.maskedAccNumber(),
                value, asOf, records.size()), records);
    }
}
