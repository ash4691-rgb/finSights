package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Adds a symbol to the watchlist with its first recorded price. */
public record WatchlistCreateRequest(
        @NotBlank @Size(max = 128, message = "Name must be 128 characters or fewer") String name,
        String tickerSymbol,
        @Size(max = 1024, message = "Notes must be 1024 characters or fewer") String notes,
        @NotNull BigDecimal price
) { }
