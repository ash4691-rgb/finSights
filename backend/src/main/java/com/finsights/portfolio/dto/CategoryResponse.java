package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.HoldingKind;
import java.math.BigDecimal;
import java.time.Instant;

public record CategoryResponse(
        String id, String name, HoldingKind kind, String description,
        BigDecimal investedValue, BigDecimal currentValue, BigDecimal profitLoss, BigDecimal profitLossPercentage,
        BigDecimal weightagePercent, BigDecimal liquidAmount, BigDecimal liquidPercent,
        BigDecimal npaAmount, BigDecimal npaPercent, int holdingCount, Instant createdAt, Instant updatedAt
) { }
