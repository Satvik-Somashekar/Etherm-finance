package com.openfinance.core.normalize;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.openfinance.core.TestData;
import com.openfinance.core.model.Category;
import com.openfinance.core.model.FiType;
import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.RecordKind;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FiNormalizerTest {

    private final FiNormalizer normalizer = new FiNormalizer();

    @Test
    void everySourceTransactionBecomesExactlyOneRecord() {
        for (SourceAccount s : TestData.sources(0)) {
            NormalizedData data = normalizer.normalize(s);
            long txns = data.records().stream().filter(r -> r.kind() == RecordKind.TRANSACTION).count();
            assertThat(txns).as(s.linkRefNumber()).isEqualTo(s.account().at("/transactions/transaction").size());
            assertThat(data.account().recordCount()).isEqualTo(data.records().size());
        }
    }

    @Test
    void recordIdsAreUniqueAcrossTheCustomer() {
        List<FinancialRecord> records = TestData.records(1);
        Set<String> ids = new HashSet<>();
        records.forEach(r -> assertThat(ids.add(r.recordId())).as(r.recordId()).isTrue());
    }

    @Test
    void depositNarrationsMapToCategories() {
        assertThat(DepositNormalizer.debitCategory("UPI/FRESHMART/groceries")).isEqualTo(Category.GROCERIES);
        assertThat(DepositNormalizer.debitCategory("ACH/SIP/KESTREL MUTUAL FUND")).isEqualTo(Category.SIP_DEBIT);
        assertThat(DepositNormalizer.debitCategory("ACH/PREMIUM/LOTUS LIFE INSURANCE")).isEqualTo(Category.PREMIUM_DEBIT);
        assertThat(DepositNormalizer.debitCategory("UPI/RENT/landlord")).isEqualTo(Category.RENT);
        assertThat(DepositNormalizer.debitCategory("ATM/CASH WITHDRAWAL")).isEqualTo(Category.CASH_WITHDRAWAL);
        assertThat(DepositNormalizer.debitCategory("UPI/SOMEONE/misc")).isEqualTo(Category.OTHER_DEBIT);
        assertThat(DepositNormalizer.creditCategory("NEFT/SALARY/NORTHWIND")).isEqualTo(Category.SALARY);
        assertThat(DepositNormalizer.counterparty("UPI/FRESHMART/groceries")).isEqualTo("FRESHMART");
        assertThat(DepositNormalizer.counterparty("ACH/SIP/KESTREL MUTUAL FUND")).isEqualTo("KESTREL MUTUAL FUND");
    }

    @Test
    void generatedDepositsHaveNoUncategorisedDebits() {
        assertThat(TestData.records(2)).filteredOn(r -> r.fiType() == FiType.DEPOSIT)
                .extracting(FinancialRecord::category)
                .doesNotContain(Category.OTHER_DEBIT, Category.OTHER_CREDIT);
    }

    @Test
    void insuranceIsCoverNotAnAsset() {
        SourceAccount policy = TestData.sources(0).stream()
                .filter(s -> s.account().get("type").asText().equals("insurance_policies")).findFirst().orElseThrow();
        NormalizedData data = normalizer.normalize(policy);
        assertThat(data.account().currentValue()).isZero();
        assertThat(data.records()).anyMatch(r -> r.kind() == RecordKind.POLICY && r.amount().signum() > 0);
    }

    @Test
    void missingRequiredFieldFailsLoudlyWithTheAccountRef() {
        SourceAccount deposit = TestData.sources(0).get(0);
        ObjectNode broken = deposit.account().deepCopy();
        ((ObjectNode) broken.at("/transactions/transaction/0")).remove("amount");
        SourceAccount bad = new SourceAccount(deposit.customerId(), "CN", deposit.fipId(), deposit.linkRefNumber(),
                deposit.maskedAccNumber(), broken);
        assertThatThrownBy(() -> normalizer.normalize(bad))
                .isInstanceOf(NormalizationException.class)
                .hasMessageContaining(deposit.linkRefNumber())
                .hasMessageContaining("amount");
    }

    @Test
    void unknownAccountTypeIsRejected() {
        ObjectNode account = new ObjectMapper().createObjectNode().put("type", "credit_card");
        assertThatThrownBy(() -> normalizer.normalize(new SourceAccount("c", "cn", "f", "l", "m", account)))
                .isInstanceOf(NormalizationException.class);
    }
}
