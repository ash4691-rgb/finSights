package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Edits a watchlist item's details — its price trail is updated separately via the price endpoint. */
public record WatchlistUpdateRequest(
        @NotBlank @Size(max = 128, message = "Name must be 128 characters or fewer") String name,
        String tickerSymbol,
        @Size(max = 1024, message = "Notes must be 1024 characters or fewer") String notes
) { }
