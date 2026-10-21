package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.TickerPreference;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.TickerPreferenceRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TickerPreferenceServiceTest {

    @Mock TickerPreferenceRepository prefs;
    @Mock CurrentUserService currentUserService;

    private TickerPreferenceService service;
    private UserAccount user;

    @BeforeEach
    void setUp() throws Exception {
        service = new TickerPreferenceService(prefs, currentUserService);
        user = new UserAccount("demo@finsights.local", "Demo");
        setField(user, "id", "u-1");
        when(currentUserService.currentUser()).thenReturn(user);
    }

    private static void setField(Object entity, String name, Object value) throws Exception {
        Field field = entity.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(entity, value);
    }

    @Test
    void starCreatesANewRowWhenNoneExistsYetAndNormalizesTheSymbol() {
        when(prefs.findByUser_IdAndSymbol("u-1", "RELIANCE.NS")).thenReturn(Optional.empty());

        service.star(" reliance.ns ");

        ArgumentCaptor<TickerPreference> captor = ArgumentCaptor.forClass(TickerPreference.class);
        verify(prefs).save(captor.capture());
        assertThat(captor.getValue().getSymbol()).isEqualTo("RELIANCE.NS");
        assertThat(captor.getValue().isStarred()).isTrue();
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void starUpdatesAnExistingRowInPlaceRatherThanDuplicatingIt() {
        TickerPreference existing = new TickerPreference();
        existing.setUser(user);
        existing.setSymbol("RELIANCE.NS");
        when(prefs.findByUser_IdAndSymbol("u-1", "RELIANCE.NS")).thenReturn(Optional.of(existing));

        service.star("RELIANCE.NS");

        assertThat(existing.isStarred()).isTrue(); // same instance, mutated
        verify(prefs).save(existing);
    }

    @Test
    void unstarClearsTheFlagWithoutTouchingExcluded() {
        TickerPreference existing = new TickerPreference();
        existing.setUser(user);
        existing.setSymbol("RELIANCE.NS");
        existing.setStarred(true);
        existing.setExcluded(true);
        when(prefs.findByUser_IdAndSymbol("u-1", "RELIANCE.NS")).thenReturn(Optional.of(existing));

        service.unstar("RELIANCE.NS");

        assertThat(existing.isStarred()).isFalse();
        assertThat(existing.isExcluded()).isTrue(); // untouched
    }

    @Test
    void excludeAndRestoreToggleTheExcludedFlagIndependentlyOfStarred() {
        TickerPreference existing = new TickerPreference();
        existing.setUser(user);
        existing.setSymbol("RELIANCE.NS");
        when(prefs.findByUser_IdAndSymbol("u-1", "RELIANCE.NS")).thenReturn(Optional.of(existing));

        service.exclude("RELIANCE.NS");
        assertThat(existing.isExcluded()).isTrue();

        service.restore("RELIANCE.NS");
        assertThat(existing.isExcluded()).isFalse();
    }

    @Test
    void starredSymbolsOnlyReturnsRowsFlaggedStarred() {
        TickerPreference starred = row("RELIANCE.NS", true, false);
        TickerPreference excluded = row("TCS.NS", false, true);
        when(prefs.findByUser_Id("u-1")).thenReturn(List.of(starred, excluded));

        assertThat(service.starredSymbols()).isEqualTo(Set.of("RELIANCE.NS"));
    }

    @Test
    void excludedSymbolsOnlyReturnsRowsFlaggedExcluded() {
        TickerPreference starred = row("RELIANCE.NS", true, false);
        TickerPreference excluded = row("TCS.NS", false, true);
        when(prefs.findByUser_Id("u-1")).thenReturn(List.of(starred, excluded));

        assertThat(service.excludedSymbols()).isEqualTo(Set.of("TCS.NS"));
    }

    private TickerPreference row(String symbol, boolean starred, boolean excluded) {
        TickerPreference p = new TickerPreference();
        p.setUser(user);
        p.setSymbol(symbol);
        p.setStarred(starred);
        p.setExcluded(excluded);
        return p;
    }
}
