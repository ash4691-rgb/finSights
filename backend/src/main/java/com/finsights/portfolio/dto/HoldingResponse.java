package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public record HoldingResponse(
        String id, String holdingId, String categoryId, String categoryName, String name, HoldingKind kind, ValuationMethod valuationMethod,
        String tickerSymbol, String broker, String ownerName, String currency,
        BigDecimal investedValue, BigDecimal currentValue, BigDecimal profitLoss, BigDecimal profitLossPercentage,
        BigDecimal quantity, BigDecimal fixedAnnualRate, CompoundingFrequency compoundingFrequency,
        LocalDate fixedRateStartDate, Boolean liquidWithinSevenDays, Boolean blocked, String description, String notes, Set<String> tags,
        Instant createdAt, Instant updatedAt, Instant priceUpdatedAt
) { }
