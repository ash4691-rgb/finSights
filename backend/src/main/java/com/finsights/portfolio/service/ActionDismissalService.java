package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.ActionDismissal;
import com.finsights.portfolio.domain.ActionDismissalStatus;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.DeferredActionResponse;
import com.finsights.portfolio.repository.ActionDismissalRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tracks which Action Centre rows the user has marked done / deferred / deleted. */
@Service
public class ActionDismissalService {

    private static final int DEFAULT_DEFER_DAYS = 7;

    // "<kind>" prefix of an action key (see ActionItemResponse.keyOf) -> a short, display-friendly
    // label — just enough to reconstruct a deferred row's text without re-deriving the full Action
    // Centre logic that originally built it.
    private static final Map<String, String> KIND_LABELS = Map.of(
            "EMI_DUE", "EMI due", "EMI_OVERDUE", "EMI overdue",
            "INTEREST_DUE", "Interest due", "INTEREST_OVERDUE", "Interest overdue",
            "FIXED_RATE_MATURED", "Matured deposit", "PRICE_UNAVAILABLE", "No live price",
            "DQ_NO_VALUE", "No current value", "DQ_NO_BROKER", "No broker",
            "DQ_FOREIGN_CURRENCY", "Foreign currency", "DQ_NO_INVESTED", "No invested amount");

    private final ActionDismissalRepository repo;
    private final HoldingRepository holdings;
    private final CurrentUserService currentUser;

    public ActionDismissalService(ActionDismissalRepository repo, HoldingRepository holdings, CurrentUserService currentUser) {
        this.repo = repo;
        this.holdings = holdings;
        this.currentUser = currentUser;
    }

    /** Keys that should not appear right now (done, deleted, or still within a deferral window). */
    @Transactional(readOnly = true)
    public Set<String> suppressedKeys() {
        LocalDate today = LocalDate.now();
        Set<String> out = new HashSet<>();
        for (ActionDismissal d : repo.findByUser_Id(currentUser.currentUser().getId())) {
            boolean active = switch (d.getStatus()) {
                case DONE, DELETED -> true;
                case DEFERRED -> d.getDeferredUntil() != null && !d.getDeferredUntil().isBefore(today);
            };
            if (active) out.add(d.getActionKey());
        }
        return out;
    }

    @Transactional
    public void apply(String key, ActionDismissalStatus status, Integer deferDays) {
        UserAccount user = currentUser.currentUser();
        ActionDismissal row = repo.findByUser_IdAndActionKey(user.getId(), key).orElseGet(() -> {
            ActionDismissal fresh = new ActionDismissal();
            fresh.setUser(user);
            fresh.setActionKey(key);
            return fresh;
        });
        row.setStatus(status);
        row.setDeferredUntil(status == ActionDismissalStatus.DEFERRED
                ? LocalDate.now().plusDays(deferDays == null || deferDays <= 0 ? DEFAULT_DEFER_DAYS : deferDays)
                : null);
        row.setUpdatedAt(Instant.now());
        repo.save(row);
    }

    public void deleteForUser(String userId) {
        repo.deleteByUser_Id(userId);
    }

    /** Currently-snoozed rows, for Signals' Radar — so a deferred item can be seen and un-snoozed
     * instead of silently waiting to reappear on its own. */
    @Transactional(readOnly = true)
    public List<DeferredActionResponse> deferredItems() {
        String userId = currentUser.currentUser().getId();
        LocalDate today = LocalDate.now();
        List<DeferredActionResponse> out = new ArrayList<>();
        for (ActionDismissal d : repo.findByUser_Id(userId)) {
            if (d.getStatus() != ActionDismissalStatus.DEFERRED
                    || d.getDeferredUntil() == null || d.getDeferredUntil().isBefore(today)) continue;
            String[] parts = d.getActionKey().split("\\|", -1);
            String kind = parts.length > 0 ? parts[0] : "";
            String holdingId = parts.length > 1 ? parts[1] : "";
            String holdingName = holdings.findByIdAndUser_Id(holdingId, userId).map(Holding::getName).orElse("—");
            out.add(new DeferredActionResponse(d.getActionKey(), kind,
                    KIND_LABELS.getOrDefault(kind, kind), holdingName, d.getDeferredUntil()));
        }
        out.sort(Comparator.comparing(DeferredActionResponse::deferredUntil));
        return out;
    }

    /** Un-snooze: clears the dismissal entirely, so the row reappears in its normal list right away. */
    @Transactional
    public void restore(String key) {
        repo.findByUser_IdAndActionKey(currentUser.currentUser().getId(), key).ifPresent(repo::delete);
    }
}
