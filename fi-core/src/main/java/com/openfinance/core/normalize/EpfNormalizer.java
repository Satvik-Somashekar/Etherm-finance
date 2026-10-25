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
import java.util.Map;

public class EpfNormalizer implements AccountNormalizer {

    @Override
    public FiType fiType() {
        return FiType.EPF;
    }

    @Override
    public NormalizedData normalize(SourceAccount source) {
        JsonNode account = source.account();
        JsonNode summary = account.path("summary");
        RecordFactory f = new RecordFactory(source, FiType.EPF);
        List<FinancialRecord> records = new ArrayList<>();
        String employer = Fields.optText(summary, "establishmentName");

        for (JsonNode t : Fields.array(account, "/transactions/transaction")) {
            boolean interest = "INTEREST".equals(Fields.text(t, "type"));
            records.add(f.record(Fields.text(t, "txnId"), RecordKind.TRANSACTION,
                    interest ? Category.EPF_INTEREST : Category.EPF_CONTRIBUTION, Direction.CREDIT,
                    Fields.date(t, "txnDate"), Fields.decimal(t, "amount"), Fields.text(t, "narration"),
                    interest ? "EPFO" : employer,
                    Map.of("employeeShare", Fields.text(t, "employeeShare"),
                            "employerShare", Fields.text(t, "employerShare"),
                            "pensionShare", Fields.text(t, "pensionShare"),
                            "wageMonth", String.valueOf(Fields.optText(t, "wageMonth")))));
        }

        BigDecimal balance = Fields.decimal(summary, "currentBalance");
        LocalDate asOf = Fields.date(summary, "balanceDate");
        records.add(f.record("SUMMARY", RecordKind.BALANCE, Category.ACCOUNT_BALANCE, null, asOf, balance,
                "EPF balance (employee + employer)", employer,
                Map.of("employeeBalance", Fields.text(summary, "employeeBalance"),
                        "employerBalance", Fields.text(summary, "employerBalance"),
                        "pensionBalance", Fields.text(summary, "pensionBalance"))));

        return new NormalizedData(new NormalizedAccount(source.linkRefNumber(), source.customerId(), source.consentId(),
                source.fipId(), FiType.EPF, source.maskedAccNumber(), "EPF - " + employer, balance, asOf,
                records.size()), records);
    }
}
