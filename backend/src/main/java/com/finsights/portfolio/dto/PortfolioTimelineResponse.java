package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Weekly portfolio history for the Insights timeline, oldest week first. */
public record PortfolioTimelineResponse(List<Week> weeks) {

    public record Week(
            LocalDate weekOf,
            BigDecimal invested,      // total invested across asset categories
            BigDecimal current,       // total current value across asset categories
            BigDecimal liabilities,   // total outstanding across liability categories
            BigDecimal netWorth,      // current − liabilities
            List<CategoryPoint> categories
    ) { }

    public record CategoryPoint(
            String categoryId,
            String categoryName,
            String kind,
            BigDecimal invested,
            BigDecimal current
    ) { }
}
