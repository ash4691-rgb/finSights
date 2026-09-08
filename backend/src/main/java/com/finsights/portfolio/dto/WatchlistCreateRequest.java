package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Adds a symbol to the watchlist with its first recorded price. */
public record WatchlistCreateRequest(
        @NotBlank @Size(min = 1, max = 128, message = "Name must be 1–128 characters") String name,
        @Size(max = 24, message = "Ticker symbol must be 24 characters or fewer") String tickerSymbol,
        @Size(max = 1024, message = "Notes must be 1024 characters or fewer") String notes,
        @NotNull BigDecimal price
) { }
