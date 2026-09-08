package com.finsights.portfolio.dto;

import com.finsights.portfolio.dto.DashboardResponse.Breakdown;
import java.math.BigDecimal;
import java.util.List;

public record InsightsResponse(
        List<Breakdown> byCategory,
        List<Breakdown> byBroker,
        List<Breakdown> byTag,
        List<Breakdown> byCurrency,
        List<Breakdown> byLiquidity,
        List<Mover> topGainers,
        List<Mover> topLosers,
        List<ActionItemResponse> actions
) {
    public record Mover(String id, String name, BigDecimal profitLoss, BigDecimal profitLossPercentage) { }
}
