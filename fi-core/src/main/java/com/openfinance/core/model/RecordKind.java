package com.openfinance.core.model;

/** What a normalised record describes. */
public enum RecordKind {
    /** A money movement on a date. */
    TRANSACTION,
    /** One position inside an investment account (a scheme or a stock), valued as of a date. */
    HOLDING,
    /** The reported value of a whole account as of a date. */
    BALANCE,
    /** An insurance policy's terms (amount is the sum assured, not a value the user holds). */
    POLICY
}
