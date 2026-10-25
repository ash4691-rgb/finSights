package com.finsights.portfolio.dto;

import java.math.BigDecimal;

/**
 * Extra inputs for a typed manual holding. Real estate uses the two unit prices (with the
 * holding's own area/areaUnit); EPFO uses the contributions, interest and expected rate.
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
        /** EPFO: what is contributed each month; drives the monthly "log last month's contribution" task. */
        BigDecimal monthlyEmployeeContribution,
        BigDecimal monthlyEmployerContribution
) {
    public ManualDetails(BigDecimal purchaseUnitPrice, BigDecimal currentUnitPrice, BigDecimal employeeContribution,
                         BigDecimal employerContribution, BigDecimal interestAccumulated, BigDecimal expectedInterestRate) {
        this(purchaseUnitPrice, currentUnitPrice, employeeContribution, employerContribution, interestAccumulated,
                expectedInterestRate, null, null);
    }

    public static final ManualDetails NONE = new ManualDetails(null, null, null, null, null, null, null, null);
}
