package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BrokersResponse(List<BrokerGroup> brokers, List<Source> sources) {

    public record BrokerGroup(
            String name, int holdingCount, BigDecimal currentValue, BigDecimal investedValue,
            BigDecimal profitLoss, Instant lastUpdated, List<String> categories, List<String> currencies
    ) { }

    public record Source(
            String key, String name, String status, String description,
            List<String> capabilities, String docsUrl
    ) { }
}
