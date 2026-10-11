package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * One equity/ETF position the CAS-extraction model found in an uploaded statement. Nothing here
 * is written to the database directly — the Holdings page pre-fills the ordinary Add Holding
 * form with these values so the user reviews and saves each one through the exact same path
 * (and the exact same validation) as typing it in by hand. See {@code CasImportService}.
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
        /** Set when this row's ISIN matches an existing holding (tagged from a previous CAS
         *  import — see {@code CasImportService.matchAgainstExisting}). A match means this row
         *  needs no review: it's already in the portfolio, so the Holdings page shows it as
         *  "successfully parsed and mapped" rather than asking the user to create it again. Null
         *  means it's new and genuinely needs a reviewed Add Holding form. */
        String matchedHoldingId
) { }
