package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the Action Centre: something the user should look at or confirm.
 * {@code kind} drives the icon/label on the client; {@code dueDate}/{@code amount}
 * are set only for the item types that have them (EMIs, maturities). {@code key}
 * is a stable identifier the client echoes back to mark the row done / deferred / deleted.
 */
public record ActionItemResponse(
        String kind,          // EMI_DUE, EMI_OVERDUE, INTEREST_DUE, FIXED_RATE_MATURED, PRICE_UNAVAILABLE, DQ_*
        String severity,      // WARN | INFO
        String title,
        String detail,
        String holdingId,
        String holdingName,
        LocalDate dueDate,
        BigDecimal amount,
        String period,        // the due date for EMI / interest items, so the client can post "mark paid"
        String key            // "<kind>|<holdingId>|<period>" — stable across requests
) {
    /** Build the stable dismissal key for an action row. */
    public static String keyOf(String kind, String holdingId, String period) {
        return kind + "|" + (holdingId == null ? "" : holdingId) + "|" + (period == null ? "" : period);
    }
}
