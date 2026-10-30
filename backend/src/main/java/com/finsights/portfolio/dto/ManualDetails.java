package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * Extra inputs for a typed manual holding. Real estate uses the two unit prices (with the
 * holding's own area/areaUnit); provident fund uses the expected rate, the monthly contribution and the accrued interest.
 * Fields that don't belong to the holding's manual type are ignored and stored as null.
 */
public record ManualDetails(
        BigDecimal purchaseUnitPrice,
        BigDecimal currentUnitPrice,
        /** Expected annual rate as a fraction (0.0825 = 8.25%). */
        BigDecimal expectedInterestRate,
        /** Provident fund: the total contributed each month; drives the monthly "confirm last month's contribution" task. */
        BigDecimal monthlyContribution,
        /** Provident fund: interest credited so far; current value = invested value + accrued interest. Zero when unset. */
        BigDecimal accruedInterest
) {
    public static final ManualDetails NONE = new ManualDetails(null, null, null, null, null);
}
