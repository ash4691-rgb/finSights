package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Adds a symbol to the watchlist with its first recorded price. */
public record WatchlistCreateRequest(
        @NotBlank String name,
        String tickerSymbol,
        String notes,
        @NotNull BigDecimal price
) { }
