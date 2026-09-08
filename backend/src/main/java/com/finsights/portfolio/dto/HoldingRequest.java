package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.ValuationMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

public record HoldingRequest(
        @NotBlank String categoryId,
        @NotBlank String name,
        @NotNull ValuationMethod valuationMethod,
        String tickerSymbol,
        @NotBlank(message = "Every holding must be mapped to a broker") String broker,
        String ownerName,
        String currency,
        BigDecimal quantity,
        BigDecimal investedValue,
        BigDecimal currentValue,
        BigDecimal fixedAnnualRate,
        CompoundingFrequency compoundingFrequency,
        LocalDate fixedRateStartDate,
        Boolean liquidWithinSevenDays,
        Boolean blocked,
        String description,
        String notes,
        Set<String> tags
) { }
