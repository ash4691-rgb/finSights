package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.PendingBrokerHolding;
import com.finsights.portfolio.domain.PendingBrokerHoldingReason;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.BrokerSnapshot;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.PendingBrokerHoldingRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrokerHoldingSyncConsumerTest {

    @Mock HoldingRepository holdings;
    @Mock PendingBrokerHoldingRepository pending;
    @Mock PriceSnapshotService priceSnapshots;

    private BrokerHoldingSyncConsumer consumer;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        consumer = new BrokerHoldingSyncConsumer(holdings, pending, priceSnapshots);
        user = new UserAccount("demo@finsights.local", "Demo");
    }

    @Test
    void anAlreadyLinkedHoldingIsUpdatedDirectlyWithoutTouchingPending() {
        Holding existing = new Holding();
        existing.setUser(user);
        existing.setCurrentValue(new BigDecimal("100.00"));
        existing.setQuantity(BigDecimal.TEN);
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.of(existing));
        BrokerSnapshot snap = new BrokerSnapshot("kite:1", "INFY", new BigDecimal("12"), new BigDecimal("150.00"), Instant.now());

        consumer.applySnapshots(user, "kite", List.of(snap));

        assertThat(existing.getQuantity()).isEqualByComparingTo("12");
        assertThat(existing.getCurrentValue()).isEqualByComparingTo("150.00");
        verify(holdings).save(existing);
        verify(priceSnapshots).record(eq(SnapshotSubject.HOLDING), any(), eq(user), eq(new BigDecimal("150.00")));
        verify(pending, never()).save(any());
    }

    @Test
    void anUnchangedValueNeverRecordsARedundantSnapshot() {
        Holding existing = new Holding();
        existing.setUser(user);
        existing.setCurrentValue(new BigDecimal("150.00"));
        existing.setQuantity(BigDecimal.TEN);
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.of(existing));
        BrokerSnapshot snap = new BrokerSnapshot("kite:1", "INFY", BigDecimal.TEN, new BigDecimal("150.00"), Instant.now());

        consumer.applySnapshots(user, "kite", List.of(snap));

        verify(priceSnapshots, never()).record(any(), any(), any(), any());
    }

    @Test
    void aGenuinelyNewInstrumentWithNoNameCollisionBecomesAPendingNewRow() {
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "indmoney:ISIN1")).thenReturn(Optional.empty());
        when(pending.findByUser_IdAndBrokerSyncRef(user.getId(), "indmoney:ISIN1")).thenReturn(Optional.empty());
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(user.getId(), "RELIANCE", "INDmoney"))
                .thenReturn(Optional.empty());
        BrokerSnapshot snap = new BrokerSnapshot("indmoney:ISIN1", "RELIANCE", BigDecimal.TEN, new BigDecimal("2500.00"), Instant.now());

        consumer.applySnapshots(user, "indmoney", List.of(snap));

        ArgumentCaptor<PendingBrokerHolding> captor = ArgumentCaptor.forClass(PendingBrokerHolding.class);
        verify(pending).save(captor.capture());
        PendingBrokerHolding saved = captor.getValue();
        assertThat(saved.getReason()).isEqualTo(PendingBrokerHoldingReason.NEW);
        assertThat(saved.getConflictingHoldingId()).isNull();
        assertThat(saved.getSuggestedName()).isEqualTo("RELIANCE");
        assertThat(saved.getBrokerKey()).isEqualTo("indmoney");
        assertThat(saved.getUser()).isEqualTo(user);
    }

    // The core reason PendingBrokerHolding exists at all: a holding entered manually before the
    // broker was ever connected must not get silently duplicated once sync starts matching it.
    @Test
    void anInstrumentMatchingAnExistingUnsyncedHoldingsNameBecomesAConflict() throws Exception {
        Holding manualHolding = new Holding();
        manualHolding.setBrokerSyncRef(null); // never synced — entered by hand
        setId(manualHolding, "holding-1");
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.empty());
        when(pending.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.empty());
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(user.getId(), "INFY", "Zerodha Kite"))
                .thenReturn(Optional.of(manualHolding));
        BrokerSnapshot snap = new BrokerSnapshot("kite:1", "INFY", BigDecimal.TEN, new BigDecimal("1500.00"), Instant.now());

        consumer.applySnapshots(user, "kite", List.of(snap));

        ArgumentCaptor<PendingBrokerHolding> captor = ArgumentCaptor.forClass(PendingBrokerHolding.class);
        verify(pending).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(PendingBrokerHoldingReason.NAME_CONFLICT);
        assertThat(captor.getValue().getConflictingHoldingId()).isEqualTo("holding-1");
    }

    @Test
    void aNameMatchAgainstAHoldingAlreadySyncedToSomethingElseIsNotTreatedAsAConflict() {
        Holding alreadySynced = new Holding();
        alreadySynced.setBrokerSyncRef("kite:999"); // linked to a DIFFERENT instrument already
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.empty());
        when(pending.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.empty());
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(user.getId(), "INFY", "Zerodha Kite"))
                .thenReturn(Optional.of(alreadySynced));
        BrokerSnapshot snap = new BrokerSnapshot("kite:1", "INFY", BigDecimal.TEN, new BigDecimal("1500.00"), Instant.now());

        consumer.applySnapshots(user, "kite", List.of(snap));

        ArgumentCaptor<PendingBrokerHolding> captor = ArgumentCaptor.forClass(PendingBrokerHolding.class);
        verify(pending).save(captor.capture());
        assertThat(captor.getValue().getReason()).isEqualTo(PendingBrokerHoldingReason.NEW);
    }

    @Test
    void aRepeatSyncOfAnAlreadyPendingInstrumentRefreshesTheSameRowRatherThanDuplicatingIt() {
        PendingBrokerHolding existingPending = new PendingBrokerHolding();
        existingPending.setReason(PendingBrokerHoldingReason.NEW);
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.empty());
        when(pending.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:1")).thenReturn(Optional.of(existingPending));
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(any(), any(), any())).thenReturn(Optional.empty());
        BrokerSnapshot snap = new BrokerSnapshot("kite:1", "INFY", new BigDecimal("20"), new BigDecimal("3000.00"), Instant.now());

        consumer.applySnapshots(user, "kite", List.of(snap));

        verify(pending).save(existingPending);
        assertThat(existingPending.getQuantity()).isEqualByComparingTo("20");
        assertThat(existingPending.getCurrentValue()).isEqualByComparingTo("3000.00");
    }

    @Test
    void aBlankDisplayNameFallsBackToTheInstrumentPartOfTheBrokerSyncRef() {
        when(holdings.findByUser_IdAndBrokerSyncRef(any(), any())).thenReturn(Optional.empty());
        when(pending.findByUser_IdAndBrokerSyncRef(any(), any())).thenReturn(Optional.empty());
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(any(), any(), any())).thenReturn(Optional.empty());
        BrokerSnapshot snap = new BrokerSnapshot("coinswitch:BTC", null, BigDecimal.ONE, null, Instant.now());

        consumer.applySnapshots(user, "coinswitch", List.of(snap));

        ArgumentCaptor<PendingBrokerHolding> captor = ArgumentCaptor.forClass(PendingBrokerHolding.class);
        verify(pending).save(captor.capture());
        assertThat(captor.getValue().getSuggestedName()).isEqualTo("BTC");
    }

    // One bad snapshot in a batch (e.g. a save that throws) must never block the rest from
    // applying — same isolation principle as BrokerSyncService.syncAll's per-connection catch.
    @Test
    void oneSnapshotThrowingNeverBlocksTheRestOfTheBatch() {
        Holding broken = new Holding();
        broken.setUser(user);
        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:broken")).thenReturn(Optional.of(broken));
        when(holdings.save(broken)).thenThrow(new RuntimeException("db exploded"));

        when(holdings.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:healthy")).thenReturn(Optional.empty());
        when(pending.findByUser_IdAndBrokerSyncRef(user.getId(), "kite:healthy")).thenReturn(Optional.empty());
        when(holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(any(), any(), any())).thenReturn(Optional.empty());

        BrokerSnapshot brokenSnap = new BrokerSnapshot("kite:broken", "BROKEN", BigDecimal.ONE, BigDecimal.TEN, Instant.now());
        BrokerSnapshot healthySnap = new BrokerSnapshot("kite:healthy", "HEALTHY", BigDecimal.ONE, BigDecimal.TEN, Instant.now());

        consumer.applySnapshots(user, "kite", List.of(brokenSnap, healthySnap));

        verify(pending).save(any()); // the healthy one still went through
    }

    private static void setId(Object entity, String id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }
}
