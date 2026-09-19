package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

public record HoldingResponse(
        String id, String holdingId, String categoryId, String categoryName, String name, HoldingKind kind, ValuationMethod valuationMethod,
        String tickerSymbol, String broker, String currency,
        BigDecimal investedValue, BigDecimal currentValue, BigDecimal profitLoss, BigDecimal profitLossPercentage,
        BigDecimal realisedProfitLoss, BigDecimal accruedIncome,
        BigDecimal quantity, BigDecimal fixedAnnualRate, CompoundingFrequency compoundingFrequency,
        LocalDate fixedRateStartDate, LocalDate fixedRateEndDate,
        RepaymentFrequency repaymentFrequency, BigDecimal emiAmount, Integer emiDayOfMonth,
        Integer loanTermMonths, LocalDate repaymentDueDate,
        Boolean liquidWithinSevenDays, Boolean blocked, String description, String notes, Set<String> tags,
        Instant createdAt, Instant updatedAt, Instant priceUpdatedAt,
        /** True when this holding's stored data is incomplete or inconsistent and a computed
         *  figure (valuation, currency conversion, …) had to fall back to a safe default instead
         *  of failing the whole request. {@code dataIssueMessage} explains what to fix. */
        boolean dataIssue, String dataIssueMessage
) {
    /** Flags this response with a data-issue message, keeping the first one found if already flagged. */
    public HoldingResponse withDataIssue(String message) {
        if (dataIssue) return this;
        return new HoldingResponse(id, holdingId, categoryId, categoryName, name, kind, valuationMethod,
                tickerSymbol, broker, currency, investedValue, currentValue, profitLoss, profitLossPercentage,
                realisedProfitLoss, accruedIncome, quantity, fixedAnnualRate, compoundingFrequency,
                fixedRateStartDate, fixedRateEndDate, repaymentFrequency, emiAmount, emiDayOfMonth,
                loanTermMonths, repaymentDueDate, liquidWithinSevenDays, blocked, description, notes, tags,
                createdAt, updatedAt, priceUpdatedAt, true, message);
    }
}
