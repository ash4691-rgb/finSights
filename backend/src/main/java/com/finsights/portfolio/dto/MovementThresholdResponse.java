package com.finsights.portfolio.dto;

import java.math.BigDecimal;

public record MovementThresholdResponse(
        BigDecimal dailyUpPercent, BigDecimal dailyDownPercent,
        BigDecimal weeklyUpPercent, BigDecimal weeklyDownPercent,
        BigDecimal monthlyUpPercent, BigDecimal monthlyDownPercent,
        BigDecimal quarterlyUpPercent, BigDecimal quarterlyDownPercent,
        BigDecimal yearlyUpPercent, BigDecimal yearlyDownPercent
) { }
