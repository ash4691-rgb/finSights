package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WatchlistResponse(
        String id, String name, String tickerSymbol, String notes,
        BigDecimal currentValue, Instant lastUpdated, Instant createdAt
) { }
