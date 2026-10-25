package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * Extra inputs for a typed manual holding. Real estate uses the two unit prices (with the
 * holding's own area/areaUnit); EPFO uses the contributions, interest and nudge-date fields.
 * Fields that don't belong to the holding's manual type are ignored and stored as null.
 */
public record ManualDetails(
        BigDecimal purchaseUnitPrice,
        BigDecimal currentUnitPrice,
        BigDecimal employeeContribution,
        BigDecimal employerContribution,
        BigDecimal interestAccumulated,
        /** Expected annual rate as a fraction (0.0825 = 8.25%). */
        BigDecimal expectedInterestRate,
        Integer interestCreditMonth,
        Integer interestCreditDay
) {
    public static final ManualDetails NONE = new ManualDetails(null, null, null, null, null, null, null, null);
}
