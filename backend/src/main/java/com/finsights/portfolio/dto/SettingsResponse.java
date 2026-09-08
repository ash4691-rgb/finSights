package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SettingsResponse(
        String email, String displayName, String phone, String country, String countryName, String baseCurrency,
        String numberFormat, Boolean notifyEmail, Boolean notifySms, Boolean notifyPush, BigDecimal notifyThresholdPercent,
        BigDecimal dailyThresholdPercent, BigDecimal weeklyThresholdPercent, BigDecimal monthlyThresholdPercent,
        BigDecimal quarterlyThresholdPercent, BigDecimal yearlyThresholdPercent,
        boolean demoMode, int holdingCount, Instant memberSince
) { }
