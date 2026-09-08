package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Edits a watchlist item's details — its price trail is updated separately via the price endpoint. */
public record WatchlistUpdateRequest(
        @NotBlank @Size(min = 1, max = 128, message = "Name must be 1–128 characters") String name,
        @Size(max = 24, message = "Ticker symbol must be 24 characters or fewer") String tickerSymbol,
        @Size(max = 1024, message = "Notes must be 1024 characters or fewer") String notes
) { }
