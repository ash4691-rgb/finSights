package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.TickerPreference;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.TickerPreferenceRepository;
import java.time.Instant;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Per-user star/exclude preferences for ticker symbols — see {@link TickerPreference}'s doc
 *  comment for why this sits apart from the global tracked-symbol pool. */
@Service
public class TickerPreferenceService {
    private final TickerPreferenceRepository prefs;
    private final CurrentUserService currentUser;

    public TickerPreferenceService(TickerPreferenceRepository prefs, CurrentUserService currentUser) {
        this.prefs = prefs;
        this.currentUser = currentUser;
    }

    @Transactional
    public void star(String symbol) { upsert(symbol, p -> p.setStarred(true)); }

    @Transactional
    public void unstar(String symbol) { upsert(symbol, p -> p.setStarred(false)); }

    /** Hides this ticker's news from the current user's Radar — a per-user preference, not a
     *  global unfollow (see TrackedSymbol's doc comment: the pool stays shared). */
    @Transactional
    public void exclude(String symbol) { upsert(symbol, p -> p.setExcluded(true)); }

    @Transactional
    public void restore(String symbol) { upsert(symbol, p -> p.setExcluded(false)); }

    public Set<String> starredSymbols() {
        return prefs.findByUser_Id(currentUser.currentUser().getId()).stream()
                .filter(TickerPreference::isStarred)
                .map(TickerPreference::getSymbol)
                .collect(Collectors.toSet());
    }

    public Set<String> excludedSymbols() {
        return prefs.findByUser_Id(currentUser.currentUser().getId()).stream()
                .filter(TickerPreference::isExcluded)
                .map(TickerPreference::getSymbol)
                .collect(Collectors.toSet());
    }

    private void upsert(String symbol, Consumer<TickerPreference> mutate) {
        String key = normalize(symbol);
        UserAccount user = currentUser.currentUser();
        TickerPreference row = prefs.findByUser_IdAndSymbol(user.getId(), key).orElseGet(TickerPreference::new);
        if (row.getId() == null) {
            row.setUser(user);
            row.setSymbol(key);
        }
        mutate.accept(row);
        row.setUpdatedAt(Instant.now());
        prefs.save(row);
    }

    private String normalize(String symbol) { return symbol == null ? "" : symbol.trim().toUpperCase(); }
}
