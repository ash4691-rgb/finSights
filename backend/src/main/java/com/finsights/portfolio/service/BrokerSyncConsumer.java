package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.BrokerSnapshot;
import java.util.List;

/**
 * Extension point for whoever owns applying a broker's daily snapshot onto {@code Holding} rows
 * — per issue #90, that's Data Onboarding's {@code HoldingService}, matching how {@code
 * HoldingService.refreshMarketPrices()} already consumes {@code MarketDataService}'s quotes.
 *
 * <p>{@link BrokerSyncScheduler} calls every Spring bean implementing this after each successful
 * fetch. Zero implementations is a valid, safe state — today's: the scheduler still runs, still
 * updates {@code BrokerConnection.lastSyncedAt}, it just has nothing to hand the snapshots to yet.
 */
public interface BrokerSyncConsumer {
    void applySnapshots(String brokerKey, List<BrokerSnapshot> snapshots);
}
