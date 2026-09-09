package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.ActionDismissal;
import com.finsights.portfolio.domain.ActionDismissalStatus;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.ActionDismissalRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tracks which Action Centre rows the user has marked done / deferred / deleted. */
@Service
public class ActionDismissalService {

    private static final int DEFAULT_DEFER_DAYS = 7;

    private final ActionDismissalRepository repo;
    private final CurrentUserService currentUser;

    public ActionDismissalService(ActionDismissalRepository repo, CurrentUserService currentUser) {
        this.repo = repo;
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
}
