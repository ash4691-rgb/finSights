package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.dto.BrokerSnapshot;
import com.finsights.portfolio.repository.BrokerConnectionRepository;
import com.finsights.portfolio.security.BrokerTokenEncryption;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * The actual "fetch this connection's holdings and hand them to every registered {@link
 * BrokerSyncConsumer}" logic — shared by {@link BrokerSyncScheduler}'s daily job ({@link
 * #syncAll}, every active connection across every broker) and {@link
 * BrokerConnectionService}'s on-demand "Sync now" button ({@link #syncOne}, a single connection
 * right now). Pulling this into its own class means both callers share one implementation of the
 * rule that matters: a failed fetch is never treated as "zero holdings."
 *
 * <p>{@link BrokerDataClient#fetchSnapshots} returning {@code Optional.empty()} means "couldn't
 * reach the broker," never "zero holdings" — conflating the two would let one transient outage
 * silently zero out every BROKER_SYNC holding's value for every user.
 */
@Service
public class BrokerSyncService {

    private static final Logger log = LoggerFactory.getLogger(BrokerSyncService.class);

    private final BrokerConnectionRepository connections;
    private final List<BrokerDataClient> dataClients;
    private final List<BrokerSyncConsumer> consumers;

    public BrokerSyncService(BrokerConnectionRepository connections, List<BrokerDataClient> dataClients, List<BrokerSyncConsumer> consumers) {
        this.connections = connections;
        this.dataClients = dataClients;
        this.consumers = consumers;
    }

    /** Every active connection, across every registered broker — the daily scheduled job's entry
     *  point. One connection throwing never blocks the rest. */
    public void syncAll() {
        if (!BrokerTokenEncryption.isConfigured()) return;
        int ok = 0;
        int failed = 0;
        int total = 0;
        for (BrokerDataClient client : dataClients) {
            List<BrokerConnection> active = connections.findAllByBrokerKey(client.brokerKey());
            total += active.size();
            for (BrokerConnection connection : active) {
                try {
                    if (syncOne(connection)) ok++; else failed++;
                } catch (RuntimeException e) {
                    failed++;
                    log.warn("Broker sync threw for connection {}", connection.getId(), e);
                    connection.setLastSyncError(e.getMessage());
                    connections.save(connection);
                }
            }
        }
        if (total > 0) log.info("Broker sync: {} of {} connection(s) synced, {} failed", ok, total, failed);
    }

    /** One specific connection, right now — used by both {@link #syncAll} and the "Sync now"
     *  button ({@code BrokerConnectionService#syncNow}). @return true if its holdings were
     *  fetched and handed off this round. */
    public boolean syncOne(BrokerConnection connection) {
        BrokerDataClient client = clientFor(connection.getBrokerKey());
        Optional<List<BrokerSnapshot>> snapshots = client.fetchSnapshots(connection);
        if (snapshots.isEmpty()) {
            connection.setLastSyncError("Could not fetch holdings from " + client.brokerKey() + " this round");
            connections.save(connection);
            return false;
        }
        for (BrokerSyncConsumer consumer : consumers) {
            consumer.applySnapshots(connection.getUser(), client.brokerKey(), snapshots.get());
        }
        connection.setLastSyncedAt(Instant.now());
        connection.setLastSyncError(null);
        connections.save(connection);
        return true;
    }

    private BrokerDataClient clientFor(String brokerKey) {
        return dataClients.stream().filter(c -> brokerKey.equals(c.brokerKey())).findFirst()
                .orElseThrow(() -> new IllegalStateException("No BrokerDataClient registered for broker: " + brokerKey));
    }
}
