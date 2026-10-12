package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.PendingBrokerHolding;
import com.finsights.portfolio.domain.PendingBrokerHoldingReason;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.BrokerSnapshot;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.PendingBrokerHoldingRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core's own {@link BrokerSyncConsumer}: applies each day's synced snapshot onto {@code Holding}
 * rows automatically wherever it safely can, and routes anything it can't onto the Action Centre
 * for a one-time human decision instead (see {@code ActionCentreService}) — never silently.
 *
 * <p>Three outcomes per snapshot:
 * <ul>
 * <li>A holding already linked to this {@code brokerSyncRef} (from a previous sync, or a resolved
 * pending item) — update its quantity/current value directly, same as {@code
 * HoldingService.refreshMarketPrices()} does for MARKET_PRICE holdings. Never touches invested
 * value: the broker doesn't reliably report cost basis, so that stays user-maintained (a
 * freshly-linked holding with no invested value yet already surfaces via the existing {@code
 * DQ_NO_INVESTED} Action Centre check).
 * <li>No holding is linked yet, but one already exists at this broker with the exact same
 * name (most likely entered manually before the broker was connected) — flagged as a {@link
 * PendingBrokerHoldingReason#NAME_CONFLICT} rather than silently creating a duplicate.
 * <li>Genuinely new — flagged as {@link PendingBrokerHoldingReason#NEW}, since creating a holding
 * needs a category and none can be safely guessed.
 * </ul>
 *
 * <p>A later sync of an already-pending instrument refreshes that same pending row in place
 * (never duplicates it) until the user resolves it, one way or the other.
 */
@Service
public class BrokerHoldingSyncConsumer implements BrokerSyncConsumer {

    private static final Logger log = LoggerFactory.getLogger(BrokerHoldingSyncConsumer.class);

    /** Mirrors BrokerService.SOURCES' display names — kept as its own small map rather than a
     *  dependency on BrokerService, since the two have no other reason to know about each other. */
    static final Map<String, String> BROKER_DISPLAY_NAMES = Map.of(
            "kite", "Zerodha Kite", "coinswitch", "CoinSwitch", "indmoney", "INDmoney", "epfo", "EPFO");

    private final HoldingRepository holdings;
    private final PendingBrokerHoldingRepository pending;
    private final PriceSnapshotService priceSnapshots;

    public BrokerHoldingSyncConsumer(HoldingRepository holdings, PendingBrokerHoldingRepository pending, PriceSnapshotService priceSnapshots) {
        this.holdings = holdings;
        this.pending = pending;
        this.priceSnapshots = priceSnapshots;
    }

    @Override
    @Transactional
    public void applySnapshots(UserAccount user, String brokerKey, List<BrokerSnapshot> snapshots) {
        for (BrokerSnapshot snap : snapshots) {
            try {
                applyOne(user, brokerKey, snap);
            } catch (RuntimeException e) {
                // One malformed snapshot (a blank ref, an out-of-range value) must never block
                // the rest of this user's sync — same principle as BrokerSyncService.syncAll's
                // own per-connection isolation.
                log.warn("Failed to apply broker-sync snapshot {} for user {}", snap.brokerSyncRef(), user.getId(), e);
            }
        }
    }

    private void applyOne(UserAccount user, String brokerKey, BrokerSnapshot snap) {
        Optional<Holding> existing = holdings.findByUser_IdAndBrokerSyncRef(user.getId(), snap.brokerSyncRef());
        if (existing.isPresent()) {
            applyToExistingHolding(existing.get(), snap);
        } else {
            upsertPending(user, brokerKey, snap);
        }
    }

    private void applyToExistingHolding(Holding holding, BrokerSnapshot snap) {
        BigDecimal oldValue = zeroIfNull(holding.getCurrentValue());
        if (snap.quantity() != null) holding.setQuantity(snap.quantity());
        if (snap.currentValue() != null) holding.setCurrentValue(snap.currentValue());
        // Read the new value off `holding` itself, not save()'s return value — a particular
        // JpaRepository implementation (or test double) isn't guaranteed to hand back anything
        // useful, as LivePriceService.upsert() already learned the hard way.
        holdings.save(holding);
        BigDecimal newValue = zeroIfNull(holding.getCurrentValue());
        if (oldValue.compareTo(newValue) != 0) {
            priceSnapshots.record(SnapshotSubject.HOLDING, holding.getId(), holding.getUser(), newValue);
        }
    }

    private void upsertPending(UserAccount user, String brokerKey, BrokerSnapshot snap) {
        PendingBrokerHolding row = pending.findByUser_IdAndBrokerSyncRef(user.getId(), snap.brokerSyncRef())
                .orElseGet(PendingBrokerHolding::new);
        String suggestedName = blankToFallback(snap.displayName(), snap.brokerSyncRef());
        row.setUser(user);
        row.setBrokerKey(brokerKey);
        row.setBrokerSyncRef(snap.brokerSyncRef());
        row.setSuggestedName(suggestedName);
        row.setQuantity(snap.quantity());
        row.setCurrentValue(snap.currentValue());
        row.setAsOf(snap.asOf());

        String brokerDisplayName = BROKER_DISPLAY_NAMES.getOrDefault(brokerKey, brokerKey);
        Optional<Holding> nameCollision = holdings
                .findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(user.getId(), suggestedName, brokerDisplayName)
                // Only a real conflict if that holding isn't already linked to some OTHER synced
                // instrument — an identically-named holding already synced elsewhere isn't this one.
                .filter(h -> h.getBrokerSyncRef() == null);
        if (nameCollision.isPresent()) {
            row.setReason(PendingBrokerHoldingReason.NAME_CONFLICT);
            row.setConflictingHoldingId(nameCollision.get().getId());
        } else {
            row.setReason(PendingBrokerHoldingReason.NEW);
            row.setConflictingHoldingId(null);
        }
        pending.save(row);
    }

    private static String blankToFallback(String displayName, String brokerSyncRef) {
        if (displayName != null && !displayName.isBlank()) return displayName.trim();
        int colon = brokerSyncRef.indexOf(':');
        return colon >= 0 && colon + 1 < brokerSyncRef.length() ? brokerSyncRef.substring(colon + 1) : brokerSyncRef;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
