package com.openfinance.core.model;

import java.util.EnumSet;
import java.util.Set;

/** The single category vocabulary every FI type is normalised into. */
public enum Category {
    // Deposit account movements
    SALARY,
    INTEREST,
    OTHER_CREDIT,
    RENT,
    GROCERIES,
    FOOD,
    FUEL,
    SHOPPING,
    UTILITIES,
    TRANSPORT,
    CASH_WITHDRAWAL,
    SIP_DEBIT,
    /** Money sent to, or received back from, the customer's own demat account. Not spending, not income. */
    INVESTMENT_DEBIT,
    INVESTMENT_CREDIT,
    PREMIUM_DEBIT,
    OTHER_DEBIT,
    // Investment and policy movements, recorded on the FIP that holds the asset
    MF_PURCHASE,
    MF_REDEMPTION,
    EQUITY_BUY,
    EQUITY_SELL,
    PREMIUM_PAYMENT,
    EPF_CONTRIBUTION,
    EPF_INTEREST,
    // Point-in-time records
    ACCOUNT_BALANCE,
    HOLDING_VALUE,
    POLICY_COVER;

    /**
     * Deposit debits that count as spending. SIP debits move money into investments, so they
     * are excluded; premium debits are included because the money leaves the user's assets.
     */
    public static final Set<Category> SPENDING = EnumSet.of(RENT, GROCERIES, FOOD, FUEL, SHOPPING, UTILITIES,
            TRANSPORT, CASH_WITHDRAWAL, PREMIUM_DEBIT, OTHER_DEBIT);
}
