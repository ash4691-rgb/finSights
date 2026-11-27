package com.finsights.portfolio.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Future;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/** {@code expectedReturnPercent} defaults to 10 and {@code monthlyContribution} to 0 when left out. */
public record GoalRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Positive BigDecimal targetAmount,
        @NotNull @Future LocalDate targetDate,
        @PositiveOrZero BigDecimal monthlyContribution,
        @DecimalMin("-50") @DecimalMax("50") BigDecimal expectedReturnPercent,
        Set<String> categoryIds
) { }
