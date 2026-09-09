package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Records the weekly portfolio snapshot for every account, every Monday at 03:00.
 * Capture is idempotent per ISO week, so a missed run (server down) is simply picked
 * up by the next run — or by the lazy capture on the next dashboard/Insights load.
 */
@Component
public class PortfolioSnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(PortfolioSnapshotScheduler.class);

    private final PortfolioSnapshotService snapshots;

    public PortfolioSnapshotScheduler(PortfolioSnapshotService snapshots) {
        this.snapshots = snapshots;
    }

    @Scheduled(cron = "0 0 3 * * MON", zone = "Asia/Kolkata")
    public void captureWeeklySnapshots() {
        int ok = 0;
        int failed = 0;
        for (UserAccount user : snapshots.allUsers()) {
            try {
                snapshots.captureFor(user, false);   // external call → its own transaction
                ok++;
            } catch (RuntimeException ex) {
                failed++;
                log.warn("Weekly portfolio snapshot failed for user {}", user.getId(), ex);
            }
        }
        log.info("Weekly portfolio snapshot: {} captured, {} failed", ok, failed);
    }
}
