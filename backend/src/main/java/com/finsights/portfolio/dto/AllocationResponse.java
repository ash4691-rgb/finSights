package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Asset categories' share of total assets against the user's targets. {@code amountToTarget} is
 * positive when the category is under target (add) and negative when over (trim); null without a
 * target. {@code complete} = targets add up to exactly 100%.
 */
public record AllocationResponse(String currency, BigDecimal totalAssets, BigDecimal targetSum, boolean complete, List<Row> rows) {
    public record Row(String categoryId, String name, BigDecimal currentValue, BigDecimal currentPercent,
                      BigDecimal targetPercent, BigDecimal driftPercent, BigDecimal amountToTarget) { }
}
