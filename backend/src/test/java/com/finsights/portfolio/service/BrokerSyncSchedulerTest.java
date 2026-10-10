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
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrokerSyncSchedulerTest {

    @Mock BrokerConnectionRepository connections;
    @Mock KiteConnectClient kite;
    @Mock BrokerSyncConsumer consumerOne;
    @Mock BrokerSyncConsumer consumerTwo;

    private BrokerSyncScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BrokerSyncScheduler(connections, kite, List.of(consumerOne, consumerTwo));
    }

    private MockedStatic<BrokerTokenEncryption> encryptionConfigured() {
        MockedStatic<BrokerTokenEncryption> mocked = mockStatic(BrokerTokenEncryption.class);
        mocked.when(BrokerTokenEncryption::isConfigured).thenReturn(true);
        return mocked;
    }

    @Test
    void syncAllIsANoOpWhenKiteIsntConfigured() {
        when(kite.isConfigured()).thenReturn(false);

        scheduler.syncAll();

        verify(connections, never()).findAllByBrokerKey(any());
    }

    @Test
    void syncAllIsANoOpWhenTheEncryptionKeyIsntConfigured() {
        when(kite.isConfigured()).thenReturn(true);
        // Real (unmocked) BrokerTokenEncryption.isConfigured() — false in this test JVM.

        scheduler.syncAll();

        verify(connections, never()).findAllByBrokerKey(any());
    }

    @Test
    void syncAllHandsFetchedSnapshotsToEveryRegisteredConsumer() {
        when(kite.isConfigured()).thenReturn(true);
        BrokerConnection connection = new BrokerConnection();
        connection.setAccessToken("token-1");
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(connection));
        when(kite.fetchHoldings("token-1")).thenReturn(Optional.of(List.of(
                new KiteConnectClient.KiteHolding(408065, "INFY", new BigDecimal("10"), new BigDecimal("1500.00")))));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        ArgumentCaptor<List<BrokerSnapshot>> captor = ArgumentCaptor.forClass(List.class);
        verify(consumerOne).applySnapshots(org.mockito.ArgumentMatchers.eq("kite"), captor.capture());
        verify(consumerTwo).applySnapshots(org.mockito.ArgumentMatchers.eq("kite"), any());
        assertThat(captor.getValue()).hasSize(1);
        BrokerSnapshot snapshot = captor.getValue().get(0);
        assertThat(snapshot.brokerSyncRef()).isEqualTo("kite:408065");
        assertThat(snapshot.quantity()).isEqualByComparingTo("10");
        assertThat(snapshot.currentValue()).isEqualByComparingTo("15000.00"); // 10 * 1500.00
        assertThat(connection.getLastSyncedAt()).isNotNull();
        assertThat(connection.getLastSyncError()).isNull();
    }

    // The one rule this scheduler exists to enforce: a connection whose fetch fails this round
    // is left completely alone — not even an empty snapshot list reaches a consumer — so one
    // transient Kite outage can never look like "every holding was sold" to whatever eventually
    // applies these snapshots onto Holding rows.
    @Test
    void aFailedFetchNeverReachesConsumersAndLeavesLastSyncedAtUntouched() {
        when(kite.isConfigured()).thenReturn(true);
        BrokerConnection connection = new BrokerConnection();
        connection.setAccessToken("token-1");
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(connection));
        when(kite.fetchHoldings("token-1")).thenReturn(Optional.empty());

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
        // Optional.of(List.of()) — Kite reached, it just genuinely reports zero holdings right
        // now — distinct from Optional.empty() (couldn't reach Kite at all), see the test above.
        when(kite.isConfigured()).thenReturn(true);
        BrokerConnection connection = new BrokerConnection();
        connection.setAccessToken("token-1");
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(connection));
        when(kite.fetchHoldings("token-1")).thenReturn(Optional.of(List.of()));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        verify(consumerOne).applySnapshots("kite", List.of());
        assertThat(connection.getLastSyncedAt()).isNotNull();
        assertThat(connection.getLastSyncError()).isNull();
    }

    @Test
    void oneConnectionThrowingNeverStopsTheOthersFromSyncing() {
        when(kite.isConfigured()).thenReturn(true);
        BrokerConnection broken = new BrokerConnection();
        broken.setAccessToken("token-broken");
        BrokerConnection healthy = new BrokerConnection();
        healthy.setAccessToken("token-healthy");
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of(broken, healthy));
        when(kite.fetchHoldings("token-broken")).thenThrow(new RuntimeException("boom"));
        when(kite.fetchHoldings("token-healthy")).thenReturn(Optional.of(List.of()));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        assertThat(healthy.getLastSyncedAt()).isNotNull();
        assertThat(broken.getLastSyncError()).isNotNull();
        verify(consumerOne, times(1)).applySnapshots(any(), any()); // only for the healthy connection
    }

    @Test
    void syncAllSkipsEntirelyWhenThereAreNoActiveConnections() {
        when(kite.isConfigured()).thenReturn(true);
        when(connections.findAllByBrokerKey("kite")).thenReturn(List.of());

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            scheduler.syncAll();
        }

        verify(kite, never()).fetchHoldings(any());
    }
}
