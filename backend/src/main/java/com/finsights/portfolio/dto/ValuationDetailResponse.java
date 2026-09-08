package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.ValuationMethod;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record ValuationDetailResponse(
        ValuationMethod method,
        BigDecimal investedValue,
        BigDecimal currentValue,
        BigDecimal profitLoss,
        BigDecimal profitLossPercentage,
        List<String> steps,
        LocalDate projectedMaturityDate,
        BigDecimal projectedMaturityValue
) { }
