package com.openfinance.core.oracle;

/**
 * Static reference data for one instrument. AA carries no sectors or index levels, so this comes
 * from a versioned reference file, not from the customer's consented data.
 *
 * @param synthetic true when the entry was made up for the demo rather than taken from a public source
 */
public record InstrumentReference(String isin, String symbol, String name, String sector, String assetClass,
                                  boolean synthetic) {
}
