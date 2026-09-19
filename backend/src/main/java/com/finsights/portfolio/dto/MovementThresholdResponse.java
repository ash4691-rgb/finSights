package com.finsights.portfolio.dto;

import java.math.BigDecimal;

public record MovementThresholdResponse(
        BigDecimal dailyPercent, BigDecimal weeklyPercent, BigDecimal monthlyPercent,
        BigDecimal quarterlyPercent, BigDecimal yearlyPercent
) { }
