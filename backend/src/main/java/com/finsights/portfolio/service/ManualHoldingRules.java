package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.ManualDetails;
import java.math.BigDecimal;
import java.math.RoundingMode;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validates a typed manual holding (real estate, EPFO) and derives its invested and current
 * value, so the client can never submit figures that disagree with the inputs behind them.
 * {@link #resolve} returns the request unchanged for a plain holding.
 */
final class ManualHoldingRules {
    private ManualHoldingRules() { }

    static HoldingRequest resolve(HoldingRequest request, boolean liability) {
        ManualType type = effectiveType(request.manualType());
        if (type == ManualType.DEFAULT) return request;
        if (liability) throw bad("A liability can't use a real-estate or EPFO type");
        if (request.valuationMethod() != ValuationMethod.MANUAL) {
            throw bad("Real-estate and EPFO holdings must use manual valuation");
        }
        if (type == ManualType.REAL_ESTATE) return realEstate(request);
        return epfo(request);
    }

    static ManualType effectiveType(ManualType type) {
        return type == null ? ManualType.DEFAULT : type;
    }

    private static HoldingRequest realEstate(HoldingRequest request) {
        if (request.area() == null || request.area().signum() <= 0) throw bad("A real-estate holding needs an area greater than zero");
        if (request.areaUnit() == null) throw bad("A real-estate holding needs an area unit");
        if (request.currency() == null || request.currency().isBlank()) throw bad("A real-estate holding needs a currency");
        ManualDetails details = detailsOf(request);
        BigDecimal purchase = details.purchaseUnitPrice();
        if (purchase == null || purchase.signum() <= 0) throw bad("A real-estate holding needs a purchase unit price greater than zero");
        BigDecimal current = details.currentUnitPrice() == null ? purchase : details.currentUnitPrice();
        if (current.signum() <= 0) throw bad("The current unit price must be greater than zero");
        ManualDetails resolved = new ManualDetails(purchase, current, null, null, null, null, null, null);
        return withValues(request, resolved, money(request.area().multiply(purchase)), money(request.area().multiply(current)));
    }

    private static HoldingRequest epfo(HoldingRequest request) {
        ManualDetails details = detailsOf(request);
        requireNotNegative(details.employeeContribution(), "Employee contribution");
        requireNotNegative(details.employerContribution(), "Employer contribution");
        requireNotNegative(details.interestAccumulated(), "Interest accumulated");
        requireRate(details.expectedInterestRate());
        requireCreditDate(details.interestCreditMonth(), details.interestCreditDay());
        BigDecimal contributions = totalContributions(request, details);
        BigDecimal interest = details.interestAccumulated() == null ? BigDecimal.ZERO : details.interestAccumulated();
        ManualDetails resolved = new ManualDetails(null, null, details.employeeContribution(), details.employerContribution(),
                interest, details.expectedInterestRate(), details.interestCreditMonth(), details.interestCreditDay());
        return withValues(request, resolved, money(contributions), money(contributions.add(interest)));
    }

    /** Employee + employer when either is given; otherwise the single "total contributions" figure. */
    private static BigDecimal totalContributions(HoldingRequest request, ManualDetails details) {
        boolean split = details.employeeContribution() != null || details.employerContribution() != null;
        if (split) return orZero(details.employeeContribution()).add(orZero(details.employerContribution()));
        requireNotNegative(request.investedValue(), "Total contributions");
        if (request.investedValue() == null) throw bad("An EPFO holding needs its total contributions");
        return request.investedValue();
    }

    private static void requireRate(BigDecimal rate) {
        if (rate == null) return;
        if (rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) > 0) throw bad("The expected interest rate must be between 0% and 100%");
    }

    private static void requireCreditDate(Integer month, Integer day) {
        if (month == null && day == null) return;
        if (month == null || day == null) throw bad("Set both the month and the day for the interest credit reminder");
        if (month < 1 || month > 12 || day < 1 || day > 31) throw bad("The interest credit date is not a valid day of the year");
    }

    private static void requireNotNegative(BigDecimal value, String label) {
        if (value != null && value.signum() < 0) throw bad(label + " can't be negative");
    }

    private static ManualDetails detailsOf(HoldingRequest request) {
        return request.manual() == null ? ManualDetails.NONE : request.manual();
    }

    private static HoldingRequest withValues(HoldingRequest r, ManualDetails details, BigDecimal invested, BigDecimal current) {
        return new HoldingRequest(r.categoryId(), r.name(), r.valuationMethod(), r.tickerSymbol(), r.broker(), r.currency(),
                r.quantity(), r.area(), r.areaUnit(), invested, current, r.fixedAnnualRate(), r.compoundingFrequency(),
                r.fixedRateStartDate(), r.fixedRateEndDate(), r.repaymentFrequency(), r.emiAmount(), r.emiDayOfMonth(),
                r.loanTermMonths(), r.repaymentDueDate(), r.liquidWithinSevenDays(), r.blocked(), r.description(),
                r.notes(), r.tags(), r.manualType(), details);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
