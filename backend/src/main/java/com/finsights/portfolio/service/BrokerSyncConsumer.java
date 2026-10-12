package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.BrokerSnapshot;
import java.util.List;

/**
 * Extension point for applying a broker's daily snapshot onto {@code Holding} rows — see {@link
 * BrokerHoldingSyncConsumer}, which auto-matches each snapshot against an existing holding by
 * {@code brokerSyncRef} and routes anything it can't cleanly match through the Action Centre.
 *
 * <p>{@link BrokerSyncService} calls every Spring bean implementing this after each successful
 * fetch, once per {@link com.finsights.portfolio.domain.BrokerConnection} — {@code user} is
 * that connection's owner, since this runs from the daily scheduled job with no request-scoped
 * "current user" to read.
 */
public interface BrokerSyncConsumer {
    void applySnapshots(UserAccount user, String brokerKey, List<BrokerSnapshot> snapshots);
}
