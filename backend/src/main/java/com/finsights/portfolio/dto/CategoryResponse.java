package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;

public record CategoryResponse(
        String id, String name, HoldingKind kind, String description, Set<ValuationMethod> allowedValuationMethods,
        BigDecimal investedValue, BigDecimal currentValue, BigDecimal profitLoss, BigDecimal profitLossPercentage,
        BigDecimal weightagePercent, BigDecimal liquidAmount, BigDecimal liquidPercent,
        BigDecimal npaAmount, BigDecimal npaPercent, int holdingCount, Instant createdAt, Instant updatedAt
) { }
