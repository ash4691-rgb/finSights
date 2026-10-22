package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.DismissedNewsItem;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.DismissedNewsItemRepository;
import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-user, per-article "cross" on a news item — see {@link DismissedNewsItem}'s doc comment for
 *  why this is deliberately separate from {@link TickerPreferenceService}'s ticker-level exclude. */
@Service
public class NewsDismissalService {
    /** Matches DismissedNewsItem.link's column length — truncate defensively rather than let an
     *  unusually long URL blow the unique index/column and turn a dismiss click into a 500. */
    private static final int MAX_LINK_LENGTH = 1024;

    private final DismissedNewsItemRepository dismissals;
    private final CurrentUserService currentUser;

    public NewsDismissalService(DismissedNewsItemRepository dismissals, CurrentUserService currentUser) {
        this.dismissals = dismissals;
        this.currentUser = currentUser;
    }

    /** Idempotent — crossing an already-dismissed article is a no-op, not a constraint violation. */
    @Transactional
    public void dismiss(String link) {
        if (link == null || link.isBlank()) return;
        String key = normalize(link);
        UserAccount user = currentUser.currentUser();
        if (dismissals.existsByUser_IdAndLink(user.getId(), key)) return;
        DismissedNewsItem row = new DismissedNewsItem();
        row.setUser(user);
        row.setLink(key);
        row.setDismissedAt(Instant.now());
        dismissals.save(row);
    }

    public Set<String> dismissedLinks() {
        return dismissals.findByUser_Id(currentUser.currentUser().getId()).stream()
                .map(DismissedNewsItem::getLink)
                .collect(Collectors.toSet());
    }

    /** Called from {@link NewsRefreshScheduler}'s regular tick — an article older than
     *  NewsService's own 14-day retention window can never resurface anyway (filtered out in
     *  headlinesFor() before this set is even consulted), so the dismissal record guarding
     *  against it is pointless to keep past that point. */
    @Transactional
    public void prune(Instant cutoff) {
        dismissals.deleteOlderThan(cutoff);
    }

    private String normalize(String link) {
        String trimmed = link.trim();
        return trimmed.length() > MAX_LINK_LENGTH ? trimmed.substring(0, MAX_LINK_LENGTH) : trimmed;
    }
}
