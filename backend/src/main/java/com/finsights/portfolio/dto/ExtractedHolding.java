package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * One equity, ETF, or mutual fund position a statement-extraction model found in an uploaded statement. Nothing
 * here is written to the database directly — the Holdings page pre-fills the ordinary Add Holding
 * form with these values so the user reviews and saves each one through the exact same path
 * (and the exact same validation) as typing it in by hand. See {@code StatementImportService}.
 */
public record ExtractedHolding(
        String instrumentName,
        String isin,
        BigDecimal quantity,
        /** Null when the statement doesn't show an average cost for this line. */
        BigDecimal averageCostPerUnit,
        String depository,
        /** Best-effort NSE/BSE ticker guessed from {@code instrumentName} via the same live-price
         *  search the ticker field on the Add Holding form already uses — null when nothing matched
         *  confidently. A real ISIN-to-ticker mapping doesn't exist yet (see issue #89's deferred
         *  list), so this is a stand-in: a suggestion the user still reviews before saving, never
         *  applied silently. */
        String tickerSymbol,
        /** Set when this row's ISIN matches an existing holding (tagged from a previous statement
         *  import — see {@code StatementImportService.matchAgainstExisting}). A match means this row
         *  needs no review: it's already in the portfolio, so the Holdings page shows it as
         *  "successfully parsed and mapped" rather than asking the user to create it again. Null
         *  means it's new and genuinely needs a reviewed Add Holding form. */
        String matchedHoldingId,
        /** What kind of instrument this row is; never null once parsed (see {@code StatementImportService}). */
        InstrumentKind kind,
        /** Total amount invested as the statement states it, null when it doesn't. Used to derive
         *  {@code averageCostPerUnit} for a mutual fund, whose statement lists a total, not a per-unit cost. */
        BigDecimal investedValue,
        /** Current market value as the statement states it, null when it doesn't. Pre-fills the review form. */
        BigDecimal currentValue,
        /** The symbol the statement prints for this line (a coin's "BTC", say), when it prints one —
         *  a better ticker-search query than the display name. Null otherwise. */
        String symbol
) {
    /** The shape every row had before the symbol existed. */
    public ExtractedHolding(String instrumentName, String isin, BigDecimal quantity, BigDecimal averageCostPerUnit,
                            String depository, String tickerSymbol, String matchedHoldingId, InstrumentKind kind,
                            BigDecimal investedValue, BigDecimal currentValue) {
        this(instrumentName, isin, quantity, averageCostPerUnit, depository, tickerSymbol, matchedHoldingId, kind,
                investedValue, currentValue, null);
    }

    /** The shape every row had before kind and amounts existed — a plain stock/ETF row. */
    public ExtractedHolding(String instrumentName, String isin, BigDecimal quantity, BigDecimal averageCostPerUnit,
                            String depository, String tickerSymbol, String matchedHoldingId) {
        this(instrumentName, isin, quantity, averageCostPerUnit, depository, tickerSymbol, matchedHoldingId,
                InstrumentKind.STOCK, null, null, null);
    }

    public ExtractedHolding withTicker(String ticker) {
        return new ExtractedHolding(instrumentName, isin, quantity, averageCostPerUnit, depository, ticker,
                matchedHoldingId, kind, investedValue, currentValue, symbol);
    }

    public ExtractedHolding withMatchedHolding(String holdingId) {
        return new ExtractedHolding(instrumentName, isin, quantity, averageCostPerUnit, depository, tickerSymbol,
                holdingId, kind, investedValue, currentValue, symbol);
    }
}
