package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * {@code country} (ISO-3166 alpha-2, e.g. "IN") drives the account's base currency — see CountryCurrencyService.
 * Every field but {@code country} is "only change it if present" — EXCEPT the five Hot-picks
 * threshold fields, which are always set from this request (null included), so clearing one back
 * to "off" from the Insights page works.
 */
public record SettingsRequest(
        @NotBlank String country,
        String displayName,
        String phone,
        String numberFormat,
        Boolean notifyEmail,
        Boolean notifySms,
        Boolean notifyPush,
        BigDecimal notifyThresholdPercent,
        BigDecimal dailyThresholdPercent,
        BigDecimal weeklyThresholdPercent,
        BigDecimal monthlyThresholdPercent,
        BigDecimal quarterlyThresholdPercent,
        BigDecimal yearlyThresholdPercent
) { }
