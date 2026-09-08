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
        @NotBlank @Size(min = 1, max = 128, message = "Name must be 1–128 characters") String name,
        @NotNull ValuationMethod valuationMethod,
        @Size(max = 24, message = "Ticker symbol must be 24 characters or fewer") String tickerSymbol,
        @NotBlank(message = "Every holding must be mapped to a broker")
        @Size(min = 1, max = 96, message = "Broker must be 1–96 characters") String broker,
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
        @Size(max = 30, message = "A holding can have at most 30 tags")
        Set<@Size(min = 1, max = 48, message = "Each tag must be 1–48 characters") String> tags
) { }
