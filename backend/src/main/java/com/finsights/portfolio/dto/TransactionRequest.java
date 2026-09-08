package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        @NotBlank String holdingId,
        @NotNull TransactionType type,
        @NotNull LocalDate date,
        BigDecimal amount,
        BigDecimal quantity,
        String notes
) { }
