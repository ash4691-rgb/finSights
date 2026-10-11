package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.dto.BrokerSnapshot;
import java.util.List;
import java.util.Optional;

/**
 * One broker's "fetch this connection's current holdings as snapshots" capability — the common
 * shape {@link BrokerSyncScheduler} dispatches through, so it never needs a broker-specific
 * branch. Adding a new broker (another {@code @Service} implementing this) never requires
 * touching the scheduler itself.
 *
 * <p>{@code Optional.empty()} from {@link #fetchSnapshots} means "couldn't fetch this round" (a
 * dead API, a rejected credential, a timeout) and must never be treated as "zero holdings" by a
 * caller — conflating the two would let one transient broker outage silently zero out every
 * BROKER_SYNC holding's value for every user. An {@code Optional} holding an empty list is a
 * genuine "the broker reports nothing held right now."
 */
public interface BrokerDataClient {
    /** Matches {@code BrokerConnection#getBrokerKey()} / {@code BrokerService.SOURCES}' key for
     *  this broker. */
    String brokerKey();

    Optional<List<BrokerSnapshot>> fetchSnapshots(BrokerConnection connection);
}
