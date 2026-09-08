package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/** Records a fresh observed price for a watchlist item. */
public record WatchlistPriceRequest(@NotNull BigDecimal price) { }
