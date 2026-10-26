package com.openfinance.core.oracle;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A term deposit. The current deposit normaliser only knows savings accounts, so this is a new shape.
 *
 * @param annualRatePercent the contracted rate as a percentage, for example 7.10
 * @param compoundingPerYear how many times a year interest compounds; 0 means paid out at maturity
 */
public record FixedDeposit(String accountId, String institution, BigDecimal principal, BigDecimal annualRatePercent,
                           int compoundingPerYear, LocalDate startDate, LocalDate maturityDate, String recordId) {
}
