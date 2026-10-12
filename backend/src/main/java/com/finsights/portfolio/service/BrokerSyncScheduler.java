package com.finsights.portfolio.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The daily trigger for {@link BrokerSyncService#syncAll()} — see that class for the actual sync
 * logic and the safety rule it enforces. Runs at 18:00 IST, after market close, so holdings are
 * settled for the day, and well before Kite's own ~6am session reset the next morning.
 *
 * <p>This class is deliberately thin: the same sync logic also runs on-demand from {@code
 * BrokerConnectionService#syncNow} (the Brokers page's "Sync now" button), so it lives in {@link
 * BrokerSyncService} rather than here.
 */
@Component
public class BrokerSyncScheduler {

    private final BrokerSyncService syncService;

    public BrokerSyncScheduler(BrokerSyncService syncService) {
        this.syncService = syncService;
    }

    @Scheduled(cron = "0 0 18 * * *", zone = "Asia/Kolkata")
    public void run() {
        syncService.syncAll();
    }
}
