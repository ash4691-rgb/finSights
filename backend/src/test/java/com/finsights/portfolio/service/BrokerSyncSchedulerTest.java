package com.finsights.portfolio.service;

import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** The scheduler itself is a thin @Scheduled trigger — see {@link BrokerSyncServiceTest} for the
 *  actual sync logic (and the safety rule it enforces) that both this and the on-demand "Sync
 *  now" button delegate to. */
@ExtendWith(MockitoExtension.class)
class BrokerSyncSchedulerTest {

    @Mock BrokerSyncService syncService;

    @Test
    void runDelegatesToBrokerSyncServiceSyncAll() {
        new BrokerSyncScheduler(syncService).run();

        verify(syncService).syncAll();
    }
}
