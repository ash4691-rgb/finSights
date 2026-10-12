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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls every linked broker connection's current holdings once a day and hands the result to
 * every registered {@link BrokerSyncConsumer} — mirrors {@link LivePriceRefreshScheduler}'s
 * existing pattern (that one refreshes market prices every 15 minutes; this one runs roughly
 * daily, per broker connection), per issue #90. Dispatches across every registered {@link
 * BrokerDataClient} (Kite, CoinSwitch, INDstocks, …) rather than knowing about any one broker
 * itself — adding a new broker never requires touching this class, only registering a new
 * BrokerDataClient bean.
 *
 * <p>Runs at 18:00 IST — after market close, so holdings are settled for the day, and well before
 * Kite's own ~6am session reset the next morning. CoinSwitch/INDstocks have no equivalent daily
 * reset, but running them on the same cadence keeps this one scheduler simple.
 *
 * <p><b>Safety rule this exists to enforce:</b> a connection whose fetch fails this round is left
 * completely untouched (not even an empty snapshot is sent to consumers) — a {@link
 * BrokerDataClient#fetchSnapshots} returning {@code Optional.empty()} means "couldn't reach the
 * broker," never "zero holdings." Conflating the two would let one transient outage silently zero
 * out every BROKER_SYNC holding's value for every user.
 */
@Component
public class BrokerSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(BrokerSyncScheduler.class);

    private final BrokerConnectionRepository connections;
    private final List<BrokerDataClient> dataClients;
    private final List<BrokerSyncConsumer> consumers;

    public BrokerSyncScheduler(BrokerConnectionRepository connections, List<BrokerDataClient> dataClients, List<BrokerSyncConsumer> consumers) {
        this.connections = connections;
        this.dataClients = dataClients;
        this.consumers = consumers;
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Kolkata")
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
                    if (syncOne(client, connection)) ok++; else failed++;
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

    /** @return true if this connection's holdings were fetched and handed off this round. */
    private boolean syncOne(BrokerDataClient client, BrokerConnection connection) {
        Optional<List<BrokerSnapshot>> snapshots = client.fetchSnapshots(connection);
        if (snapshots.isEmpty()) {
            connection.setLastSyncError("Could not fetch holdings from " + client.brokerKey() + " this round");
            connections.save(connection);
            return false;
        }
        for (BrokerSyncConsumer consumer : consumers) {
            consumer.applySnapshots(client.brokerKey(), snapshots.get());
        }
        connection.setLastSyncedAt(Instant.now());
        connection.setLastSyncError(null);
        connections.save(connection);
        return true;
    }
}
