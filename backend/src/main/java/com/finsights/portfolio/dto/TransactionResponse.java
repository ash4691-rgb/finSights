package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.TransactionType;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record TransactionResponse(
        String id, String holdingId, String holdingName, String categoryId, String categoryName,
        String broker, String currency, TransactionType type, LocalDate date, BigDecimal amount,
        BigDecimal quantity, String notes, Instant createdAt
) { }
