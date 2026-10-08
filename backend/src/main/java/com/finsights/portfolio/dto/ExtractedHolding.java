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
        String depository
) { }
