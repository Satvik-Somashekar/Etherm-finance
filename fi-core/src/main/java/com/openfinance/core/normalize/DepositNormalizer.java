package com.openfinance.core.normalize;

import com.fasterxml.jackson.databind.JsonNode;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.Direction;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.NormalizedAccount;
import com.openfinance.core.model.RecordKind;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class DepositNormalizer implements AccountNormalizer {

    /** Narration rules, checked in order. The first fragment found decides the category. */
    private static final List<Map.Entry<String, Category>> DEBIT_RULES = List.of(
            Map.entry("ACH/SIP/", Category.SIP_DEBIT),
            Map.entry("NEFT/DEMAT/", Category.INVESTMENT_DEBIT),
            Map.entry("ACH/PREMIUM/", Category.PREMIUM_DEBIT),
            Map.entry("/RENT/", Category.RENT),
            Map.entry("ATM/", Category.CASH_WITHDRAWAL),
            Map.entry("GROCER", Category.GROCERIES),
            Map.entry("FOOD", Category.FOOD),
            Map.entry("FUEL", Category.FUEL),
            Map.entry("SHOPPING", Category.SHOPPING),
            Map.entry("BILL", Category.UTILITIES),
            Map.entry("TRAVEL", Category.TRANSPORT));

    @Override
    public FiType fiType() {
        return FiType.DEPOSIT;
    }

    @Override
    public NormalizedData normalize(SourceAccount source) {
        JsonNode account = source.account();
        RecordFactory f = new RecordFactory(source, FiType.DEPOSIT);
        List<FinancialRecord> records = new ArrayList<>();

        for (JsonNode t : Fields.array(account, "/transactions/transaction")) {
            Direction direction = Direction.valueOf(Fields.text(t, "type"));
            String narration = Fields.text(t, "narration");
            Category category = direction == Direction.CREDIT ? creditCategory(narration) : debitCategory(narration);
            records.add(f.record(Fields.text(t, "txnId"), RecordKind.TRANSACTION, category, direction,
                    Fields.date(t, "transactionTimestamp"), Fields.decimal(t, "amount"), narration,
                    counterparty(narration), Map.of("mode", String.valueOf(Fields.optText(t, "mode")))));
        }

        JsonNode summary = account.path("summary");
        BigDecimal balance = Fields.decimal(summary, "currentBalance");
        LocalDate asOf = Fields.date(summary, "balanceDateTime");
        String type = Fields.optText(summary, "type");
        records.add(f.record("SUMMARY", RecordKind.BALANCE, Category.ACCOUNT_BALANCE, null, asOf, balance,
                (type == null ? "Deposit" : capitalise(type)) + " account balance", source.fipId(),
                Map.of("ifscCode", String.valueOf(Fields.optText(summary, "ifscCode")))));

        String label = (type == null ? "Deposit" : capitalise(type)) + " account " + source.maskedAccNumber();
        return new NormalizedData(new NormalizedAccount(source.linkRefNumber(), source.customerId(), source.consentId(),
                source.fipId(), FiType.DEPOSIT, source.maskedAccNumber(), label, balance, asOf, records.size()), records);
    }

    static Category debitCategory(String narration) {
        String upper = narration.toUpperCase(Locale.ROOT);
        for (Map.Entry<String, Category> rule : DEBIT_RULES) {
            if (upper.contains(rule.getKey())) {
                return rule.getValue();
            }
        }
        return Category.OTHER_DEBIT;
    }

    static Category creditCategory(String narration) {
        String upper = narration.toUpperCase(Locale.ROOT);
        if (upper.contains("NEFT/DEMAT/")) {
            return Category.INVESTMENT_CREDIT;
        }
        if (upper.contains("SALARY")) {
            return Category.SALARY;
        }
        if (upper.contains("INT.PD") || upper.contains("INTEREST")) {
            return Category.INTEREST;
        }
        return Category.OTHER_CREDIT;
    }

    /** UPI narrations name the payee second; NEFT and ACH name the other party third. */
    static String counterparty(String narration) {
        String[] parts = narration.split("/");
        if (parts.length < 3) {
            return null;
        }
        return "UPI".equals(parts[0]) ? parts[1] : parts[2];
    }

    static String capitalise(String s) {
        return s.isEmpty() ? s : s.charAt(0) + s.substring(1).toLowerCase(Locale.ROOT);
    }
}
