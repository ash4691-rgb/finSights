package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.ValuationMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

public record HoldingRequest(
        @NotBlank String categoryId,
        @NotBlank @Size(max = 128, message = "Name must be 128 characters or fewer") String name,
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
        @Size(max = 1024, message = "Description must be 1024 characters or fewer") String description,
        String notes,
        Set<@Size(max = 48, message = "Each tag must be 48 characters or fewer") String> tags
) { }
