package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.GokuAllowedUser;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.GokuAccessResult;
import com.finsights.portfolio.dto.GokuModelTier;
import com.finsights.portfolio.dto.GokuUserSearchResult;
import com.finsights.portfolio.repository.GokuAllowedUserRepository;
import com.finsights.portfolio.repository.UserAccountRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GokuAllowlistServiceTest {

    @Mock GokuAllowedUserRepository repository;
    @Mock UserAccountRepository users;

    private GokuAllowlistService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GokuAllowlistService(repository, users);
        set("seedAllowlist", "ash4691@gmail.com,demo@finsights.local");
        set("modelHaiku", "claude-haiku-4-5-20251001");
        set("modelSonnet", "claude-sonnet-5");
        set("modelOpus", "claude-opus-5-5");
    }

    private void set(String field, Object value) throws Exception {
        Field f = GokuAllowlistService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    void seedsTheDefaultAllowlistOnlyWhenTheTableIsEmpty() {
        when(repository.count()).thenReturn(0L);

        service.seedIfEmpty();

        verify(repository, times(1)).save(argThatEmail("ash4691@gmail.com"));
        verify(repository, times(1)).save(argThatEmail("demo@finsights.local"));
    }

    @Test
    void doesNotReseedOnceTheTableHasRows() {
        when(repository.count()).thenReturn(1L);

        service.seedIfEmpty();

        verify(repository, never()).save(any());
    }

    @Test
    void isAllowedDelegatesToTheRepositoryCaseInsensitively() {
        when(repository.existsByEmailIgnoreCase("someone@gmail.com")).thenReturn(true);

        assertThat(service.isAllowed("someone@gmail.com")).isTrue();
        assertThat(service.isAllowed(null)).isFalse();
        assertThat(service.isAllowed("")).isFalse();
    }

    @Test
    void modelForFallsBackToHaikuWhenNoOverrideIsSet() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));

        assertThat(service.modelFor("someone@gmail.com")).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void modelForFallsBackToHaikuForAnUnknownOrBlankEmail() {
        when(repository.findByEmailIgnoreCase("nobody@gmail.com")).thenReturn(Optional.empty());

        assertThat(service.modelFor("nobody@gmail.com")).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(service.modelFor(null)).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(service.modelFor("")).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void modelForReturnsTheUsersAssignedModel() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        user.setModel("claude-opus-5-5");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));

        assertThat(service.modelFor("someone@gmail.com")).isEqualTo("claude-opus-5-5");
    }

    @Test
    void currentTierIsNoAccessWhenNotOnTheAllowlist() {
        when(repository.findByEmailIgnoreCase("nobody@gmail.com")).thenReturn(Optional.empty());

        assertThat(service.currentTier("nobody@gmail.com")).isEqualTo(GokuModelTier.NO_ACCESS);
    }

    @Test
    void currentTierReflectsTheAssignedModel() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        user.setModel("claude-sonnet-5");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));

        assertThat(service.currentTier("someone@gmail.com")).isEqualTo(GokuModelTier.SONNET);
    }

    @Test
    void setAccessToNoAccessRemovesTheUser() {
        GokuAccessResult result = service.setAccess(" Someone@Gmail.com ", GokuModelTier.NO_ACCESS, "ash4691@gmail.com");

        verify(repository).deleteByEmailIgnoreCase("someone@gmail.com");
        verify(repository, never()).save(any());
        assertThat(result.tier()).isEqualTo(GokuModelTier.NO_ACCESS);
        assertThat(result.email()).isEqualTo("someone@gmail.com");
    }

    @Test
    void setAccessAddsANewUserWithTheAssignedTiersModel() {
        when(repository.findByEmailIgnoreCase("new@gmail.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GokuAccessResult result = service.setAccess(" New@Gmail.com ", GokuModelTier.OPUS, "ash4691@gmail.com");

        assertThat(result.tier()).isEqualTo(GokuModelTier.OPUS);
        verify(repository).save(argThat(u -> u != null && "claude-opus-5-5".equals(u.getModel())));
    }

    @Test
    void setAccessChangesAnExistingUsersModel() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.setAccess("someone@gmail.com", GokuModelTier.SONNET, "ash4691@gmail.com");

        assertThat(user.getModel()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void groupedByTierBucketsEveryoneIncludingLegacyNullModelRows() {
        GokuAllowedUser noOverride = new GokuAllowedUser("a@x.com", "seed");
        GokuAllowedUser sonnetUser = new GokuAllowedUser("b@x.com", "ash4691@gmail.com");
        sonnetUser.setModel("claude-sonnet-5");
        GokuAllowedUser opusUser = new GokuAllowedUser("c@x.com", "ash4691@gmail.com");
        opusUser.setModel("claude-opus-5-5");
        when(repository.findAllByOrderByEmailAsc()).thenReturn(List.of(noOverride, sonnetUser, opusUser));

        Map<GokuModelTier, List<String>> grouped = service.groupedByTier();

        assertThat(grouped.get(GokuModelTier.HAIKU)).containsExactly("a@x.com");
        assertThat(grouped.get(GokuModelTier.SONNET)).containsExactly("b@x.com");
        assertThat(grouped.get(GokuModelTier.OPUS)).containsExactly("c@x.com");
    }

    @Test
    void searchUsersRequiresAtLeastTwoCharacters() {
        assertThat(service.searchUsers("a")).isEmpty();
        assertThat(service.searchUsers(null)).isEmpty();
        verify(users, never()).findTop10ByEmailContainingIgnoreCaseOrderByEmailAsc(any());
    }

    @Test
    void searchUsersTagsEachMatchWithItsCurrentTier() {
        when(users.findTop10ByEmailContainingIgnoreCaseOrderByEmailAsc("ash"))
                .thenReturn(List.of(new UserAccount("ash4691@gmail.com", "Ash")));
        when(repository.findByEmailIgnoreCase("ash4691@gmail.com")).thenReturn(Optional.empty());

        List<GokuUserSearchResult> result = service.searchUsers("ash");

        assertThat(result).containsExactly(new GokuUserSearchResult("ash4691@gmail.com", "Ash", GokuModelTier.NO_ACCESS));
    }

    private static GokuAllowedUser argThatEmail(String email) {
        return argThat(u -> u != null && email.equals(u.getEmail()));
    }
}
