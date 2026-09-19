package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** {@code currency} is the currency {@code currentValue} is expressed in — the tracked symbol's own
 * currency by default, or a page-level display currency once converted (see WatchlistService). */
public record WatchlistResponse(
        String id, String name, String tickerSymbol, String notes,
        BigDecimal currentValue, String currency, Instant lastUpdated, Instant createdAt
) { }
