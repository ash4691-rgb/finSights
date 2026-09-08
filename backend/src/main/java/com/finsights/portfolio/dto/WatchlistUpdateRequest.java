package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;

/** Edits a watchlist item's details — its price trail is updated separately via the price endpoint. */
public record WatchlistUpdateRequest(
        @NotBlank String name,
        String tickerSymbol,
        String notes
) { }
