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
 * Pulls every linked Kite account's current holdings once a day and hands the result to every
 * registered {@link BrokerSyncConsumer} — mirrors {@link LivePriceRefreshScheduler}'s existing
 * pattern (that one refreshes market prices every 15 minutes; this one runs roughly daily, per
 * broker connection, against Kite's holdings endpoint), per issue #90.
 *
 * <p>Runs at 18:00 IST — after market close, so {@code last_price}/holdings are settled for the
 * day, and well before Kite's own ~6am session reset the next morning.
 *
 * <p><b>Safety rule this exists to enforce:</b> a connection whose fetch fails this round is left
 * completely untouched (not even an empty snapshot is sent to consumers) — {@link
 * KiteConnectClient#fetchHoldings} returning {@code Optional.empty()} means "couldn't reach Kite,"
 * never "zero holdings." Conflating the two would let one transient Kite outage silently zero out
 * every BROKER_SYNC holding's value for every user — exactly the kind of "trust new data blindly"
 * failure issue #90's own "a snapshot always wins" rule doesn't intend to cover.
 */
@Component
public class BrokerSyncScheduler {

    private static final Logger log = LoggerFactory.getLogger(BrokerSyncScheduler.class);
    private static final String KITE = "kite";

    private final BrokerConnectionRepository connections;
    private final KiteConnectClient kite;
    private final List<BrokerSyncConsumer> consumers;

    public BrokerSyncScheduler(BrokerConnectionRepository connections, KiteConnectClient kite, List<BrokerSyncConsumer> consumers) {
        this.connections = connections;
        this.kite = kite;
        this.consumers = consumers;
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Kolkata")
    public void syncAll() {
        if (!kite.isConfigured() || !BrokerTokenEncryption.isConfigured()) return;
        List<BrokerConnection> active = connections.findAllByBrokerKey(KITE);
        if (active.isEmpty()) return;
        int ok = 0;
        int failed = 0;
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
        log.info("Broker sync: {} of {} connection(s) synced, {} failed", ok, active.size(), failed);
    }

    /** @return true if this connection's holdings were fetched and handed off this round. */
    private boolean syncOne(BrokerConnection connection) {
        Optional<List<KiteConnectClient.KiteHolding>> holdings = kite.fetchHoldings(connection.getAccessToken());
        if (holdings.isEmpty()) {
            connection.setLastSyncError("Could not fetch holdings from Kite this round");
            connections.save(connection);
            return false;
        }
        Instant now = Instant.now();
        List<BrokerSnapshot> snapshots = holdings.get().stream()
                .map(h -> new BrokerSnapshot("kite:" + h.instrumentToken(), h.quantity(),
                        h.lastPrice() != null ? h.lastPrice().multiply(h.quantity()) : null, now))
                .toList();
        for (BrokerSyncConsumer consumer : consumers) {
            consumer.applySnapshots(KITE, snapshots);
        }
        connection.setLastSyncedAt(now);
        connection.setLastSyncError(null);
        connections.save(connection);
        return true;
    }
}
