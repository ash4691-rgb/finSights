package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * Every field is "always set from this request" (null included), so clearing a threshold back to
 * "off" from the Hot Picks page works — mirrors {@link SettingsRequest}'s convention.
 */
public record MovementThresholdRequest(
        BigDecimal dailyPercent, BigDecimal weeklyPercent, BigDecimal monthlyPercent,
        BigDecimal quarterlyPercent, BigDecimal yearlyPercent
) { }
