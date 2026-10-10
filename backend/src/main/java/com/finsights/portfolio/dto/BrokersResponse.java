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
            List<String> capabilities, String docsUrl,
            // Only meaningful for a source with a real connect flow (Kite in v1) — false/null for
            // everything still PLANNED. BrokerService fills these in per-request from the current
            // user's own BrokerConnection; the static source list itself never carries them.
            boolean connectable, boolean connected, Instant lastSyncedAt, boolean needsReauth
    ) { }
}
