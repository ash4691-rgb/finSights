package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * Every field is "always set from this request" (null included), so clearing a threshold back to
 * "off" from the Top movers page works — mirrors {@link SettingsRequest}'s convention.
 */
public record MovementThresholdRequest(
        BigDecimal dailyUpPercent, BigDecimal dailyDownPercent,
        BigDecimal weeklyUpPercent, BigDecimal weeklyDownPercent,
        BigDecimal monthlyUpPercent, BigDecimal monthlyDownPercent,
        BigDecimal quarterlyUpPercent, BigDecimal quarterlyDownPercent,
        BigDecimal yearlyUpPercent, BigDecimal yearlyDownPercent
) { }
