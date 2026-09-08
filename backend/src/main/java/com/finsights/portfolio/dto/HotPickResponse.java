package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/** A holding or watchlist item whose movement broke one of the user's configured thresholds. */
public record HotPickResponse(
        String subjectType, // "HOLDING" | "WATCHLIST"
        String id,
        String name,
        String categoryName, // null for watchlist items
        String tickerSymbol,
        BigDecimal currentValue,
        String currency, // null for watchlist items — they track a bare price, not a currency amount
        List<PeriodMovement> triggered
) {
    public record PeriodMovement(String period, BigDecimal percent, BigDecimal thresholdPercent) { }
}
