package com.openfinance.core.oracle;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One purchase of a stock, kept separately so holding periods and tax flags can be computed.
 *
 * @param recordId the normalised transaction record this lot was derived from, for citation
 */
public record EquityLot(String lotId, String accountId, String isin, BigDecimal unitsBought, BigDecimal unitsRemaining,
                        LocalDate purchaseDate, BigDecimal purchasePrice, String recordId) {
}
