package com.finsights.portfolio.dto;

/**
 * What kind of market-linked instrument an extracted statement row is. Drives how the row is
 * identified (a mutual fund folio, a coin or a commodity has no ISIN in most exports — only a name)
 * and how its ticker is guessed (AMFI scheme code, an NSE/BSE listing, or an INR crypto pair).
 */
public enum InstrumentKind {
    STOCK, ETF, MUTUAL_FUND, CRYPTO, COMMODITY;

    /** Kinds identified by name rather than ISIN, and whose repeated lines are one position split
     *  across folios/wallets/lots — see {@code StatementRows.consolidate}. */
    public boolean identifiedByName() { return this != STOCK && this != ETF; }
}
