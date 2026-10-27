package com.openfinance.core.compute;

/** The question types the compute layer can answer. */
public enum Intent {
    /** Total deposit spending in a period, broken down by category. */
    SPEND_TOTAL,
    /** Spending in specific categories (rent, groceries, ...). */
    SPEND_CATEGORY,
    /** The largest individual spends in a period. */
    TOP_EXPENSES,
    /** Salary (or other named credits) received. */
    INCOME,
    /** Insurance premiums paid, by policy. */
    PREMIUMS_PAID,
    /** Money put into mutual funds, by scheme. */
    MF_INVESTED,
    /** EPF contributions credited, split into employee and employer shares. */
    EPF_CONTRIBUTIONS,
    /** Current value of accounts; across all asset types this is net worth. */
    BALANCE,
    /** Current value of individual mutual fund or equity positions. */
    HOLDINGS,
    /** Sum assured on insurance policies. */
    INSURANCE_COVER,
    /** Share of salary left after spending, with the salary and spending it was computed from. */
    SAVINGS_RATE,
    /** Where spending went, grouped by merchant or payee. */
    TOP_MERCHANTS,
    /** Spending month by month, with the monthly average. */
    SPEND_TREND,
    /** The customer asked for their transactions as a file; the answer carries a download. */
    EXPORT_TRANSACTIONS,
    /** The customer asked for a monthly report as a file; the answer carries a download. */
    EXPORT_REPORT,
    /**
     * A practical suggestion for spending less or saving more, built from the customer's own computed
     * spending. The suggestion is the only thing here that is not a record total; the figures behind it are.
     */
    ADVICE,
    /** The question did not match anything the compute layer can answer. */
    UNSUPPORTED
}
