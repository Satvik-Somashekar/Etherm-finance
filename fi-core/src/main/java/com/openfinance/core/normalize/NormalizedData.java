package com.openfinance.core.normalize;

import com.openfinance.core.model.FinancialRecord;
import com.openfinance.core.model.NormalizedAccount;

import java.util.List;

public record NormalizedData(NormalizedAccount account, List<FinancialRecord> records) {
}
