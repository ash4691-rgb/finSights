package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record BrokersResponse(List<BrokerGroup> brokers, List<Source> sources) {

    public record BrokerGroup(
            String name, int holdingCount, BigDecimal currentValue, BigDecimal investedValue,
            BigDecimal profitLoss, BigDecimal profitLossPercentage, Instant lastUpdated, List<String> categories, List<String> currencies
    ) { }

    public record Source(
            String key, String name, String status, String description,
            List<String> capabilities, String docsUrl,
            // Only meaningful for a source with a real connect flow — false/null for everything
            // still PLANNED. BrokerService fills these in per-request from the current user's own
            // BrokerConnection; the static source list itself never carries them.
            boolean connectable, boolean connected, Instant lastSyncedAt, boolean needsReauth,
            // How the frontend should start a connection for this source: "REDIRECT" (Kite — a
            // central developer app, OAuth-style login handoff) or "API_KEY_SECRET"/"API_KEY_ONLY"
            // (CoinSwitch/INDstocks — no central app; the user pastes a credential they generated
            // themselves). Null for anything not connectable.
            String connectFlow
    ) { }
}
