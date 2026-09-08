package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the Action Centre: something the user should look at or confirm.
 * {@code kind} drives the icon/label on the client; {@code dueDate}/{@code amount}
 * are set only for the item types that have them (EMIs, maturities).
 */
public record ActionItemResponse(
        String kind,          // EMI_DUE, EMI_OVERDUE, FIXED_RATE_MATURED, PRICE_UNAVAILABLE, DATA_QUALITY
        String severity,      // WARN | INFO
        String title,
        String detail,
        String holdingId,
        String holdingName,
        LocalDate dueDate,
        BigDecimal amount,
        String period         // YYYY-MM for EMI items, so the client can post "mark paid"
) { }
