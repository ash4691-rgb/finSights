package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.TransactionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record TransactionRequest(
        @NotBlank String holdingId,
        @NotNull TransactionType type,
        @NotNull LocalDate date,
        BigDecimal amount,
        BigDecimal quantity,
        @Size(max = 1024, message = "Notes must be 1024 characters or fewer") String notes
) { }
