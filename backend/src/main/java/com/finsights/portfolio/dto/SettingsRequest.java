package com.finsights.portfolio.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import java.math.BigDecimal;

/**
 * {@code country} (ISO-3166 alpha-2, e.g. "IN") drives the account's base currency — see CountryCurrencyService.
 * Every field but {@code country} is "only change it if present" — EXCEPT the five Hot-picks
 * threshold fields, which are always set from this request (null included), so clearing one back
 * to "off" from the Insights page works. {@code @DecimalMin} still allows null (= off) through;
 * it only rejects a negative percentage.
 */
public record SettingsRequest(
        @NotBlank String country,
        String displayName,
        String phone,
        String numberFormat,
        Boolean notifyEmail,
        Boolean notifySms,
        Boolean notifyPush,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal notifyThresholdPercent,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal dailyThresholdPercent,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal weeklyThresholdPercent,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal monthlyThresholdPercent,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal quarterlyThresholdPercent,
        @DecimalMin(value = "0", message = "must not be negative") BigDecimal yearlyThresholdPercent
) { }
