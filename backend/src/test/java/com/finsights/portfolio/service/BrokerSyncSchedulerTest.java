package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.dto.BrokerSnapshot;
import com.finsights.portfolio.repository.BrokerConnectionRepository;
import com.finsights.portfolio.security.BrokerTokenEncryption;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrokerSyncSchedulerTest {

    @Mock BrokerConnectionRepository connections;
    @Mock BrokerDataClient kiteClient;
    @Mock BrokerDataClient coinSwitchClient;
    @Mock BrokerSyncConsumer consumerOne;
    @Mock BrokerSyncConsumer consumerTwo;

    private BrokerSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BrokerSyncScheduler(connections, List.of(kiteClient, coinSwitchClient), List.of(consumerOne, consumerTwo));
    }

    private MockedStatic<BrokerTokenEncryption> encryptionConfigured() {
        MockedStatic<BrokerTokenEncryption> mocked = mockStatic(BrokerTokenEncryption.class);
        mocked.when(BrokerTokenEncryption::isConfigured).thenReturn(true);
        return mocked;
    }

    @Test
    void syncAllIsANoOpWhenTheEncryptionKeyIsntConfigured() {
        // Real (unmocked) BrokerTokenEncryption.isConfigured() — false in this test JVM.
        scheduler.syncAll();

        verify(connections, never()).findAllByBrokerKey(any());
    }

    @Test
    void syncAllDispatchesToEveryRegisteredBrokerDataClient() {
        when(kiteClient.brokerKey()).thenReturn("kite");
        when(coinSwitchClient.brokerKey()).thenReturn("coinswitch");
        BrokerConnection kiteConnection = new BrokerConnection();
        BrokerConnection coinConnection = new BrokerConnection();
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(kiteConnection));
        when(connections.findAllByBrokerKey("coinswitch")).thenReturn(List.of(coinConnection));
        BrokerSnapshot kiteSnapshot = new BrokerSnapshot("kite:1", BigDecimal.TEN, null, Instant.now());
        BrokerSnapshot coinSnapshot = new BrokerSnapshot("coinswitch:BTC", BigDecimal.ONE, null, Instant.now());
        when(kiteClient.fetchSnapshots(kiteConnection)).thenReturn(Optional.of(List.of(kiteSnapshot)));
        when(coinSwitchClient.fetchSnapshots(coinConnection)).thenReturn(Optional.of(List.of(coinSnapshot)));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        verify(consumerOne).applySnapshots("kite", List.of(kiteSnapshot));
        verify(consumerOne).applySnapshots("coinswitch", List.of(coinSnapshot));
        verify(consumerTwo).applySnapshots("kite", List.of(kiteSnapshot));
        verify(consumerTwo).applySnapshots("coinswitch", List.of(coinSnapshot));
        assertThat(kiteConnection.getLastSyncedAt()).isNotNull();
        assertThat(coinConnection.getLastSyncedAt()).isNotNull();
    }

    // The one rule this scheduler exists to enforce: a connection whose fetch fails this round
    // is left completely alone — not even an empty snapshot list reaches a consumer — so one
    // transient broker outage can never look like "every holding was sold" to whatever eventually
    // applies these snapshots onto Holding rows.
    @Test
    void aFailedFetchNeverReachesConsumersAndLeavesLastSyncedAtUntouched() {
        when(kiteClient.brokerKey()).thenReturn("kite");
        BrokerConnection connection = new BrokerConnection();
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(connection));
        when(kiteClient.fetchSnapshots(connection)).thenReturn(Optional.empty());

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        verify(consumerOne, never()).applySnapshots(any(), any());
        verify(consumerTwo, never()).applySnapshots(any(), any());
        assertThat(connection.getLastSyncedAt()).isNull();
        assertThat(connection.getLastSyncError()).isNotNull();
    }

    @Test
    void aGenuinelyEmptyHoldingsResponseStillCountsAsASuccessfulSync() {
        // Optional.of(List.of()) — the broker was reached, it just genuinely reports zero holdings
        // right now — distinct from Optional.empty() (couldn't reach it at all), see the test above.
        when(kiteClient.brokerKey()).thenReturn("kite");
        BrokerConnection connection = new BrokerConnection();
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(connection));
        when(kiteClient.fetchSnapshots(connection)).thenReturn(Optional.of(List.of()));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        verify(consumerOne).applySnapshots("kite", List.of());
        assertThat(connection.getLastSyncedAt()).isNotNull();
        assertThat(connection.getLastSyncError()).isNull();
    }

    @Test
    void oneConnectionThrowingNeverStopsTheOthersFromSyncing() {
        when(kiteClient.brokerKey()).thenReturn("kite");
        BrokerConnection broken = new BrokerConnection();
        BrokerConnection healthy = new BrokerConnection();
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(broken, healthy));
        when(kiteClient.fetchSnapshots(broken)).thenThrow(new RuntimeException("boom"));
        when(kiteClient.fetchSnapshots(healthy)).thenReturn(Optional.of(List.of()));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        assertThat(healthy.getLastSyncedAt()).isNotNull();
        assertThat(broken.getLastSyncError()).isNotNull();
        verify(consumerOne, times(1)).applySnapshots(any(), any()); // only for the healthy connection
    }

    @Test
    void syncAllSkipsClientsWithNoActiveConnections() {
        when(kiteClient.brokerKey()).thenReturn("kite");
        when(coinSwitchClient.brokerKey()).thenReturn("coinswitch");
        when(connections.findAllByBrokerKey(any())).thenReturn(List.of());

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        verify(kiteClient, never()).fetchSnapshots(any());
        verify(coinSwitchClient, never()).fetchSnapshots(any());
    }
}
