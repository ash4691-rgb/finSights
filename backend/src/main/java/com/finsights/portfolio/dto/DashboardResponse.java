package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

public record DashboardResponse(
        BigDecimal netWorth, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal investedAssets,
        BigDecimal portfolioProfitLoss, List<Breakdown> byCategory, List<Breakdown> byBroker, List<Breakdown> byTag
) {
    public record Breakdown(String label, BigDecimal value, BigDecimal investedValue, BigDecimal profitLoss) { }
}

