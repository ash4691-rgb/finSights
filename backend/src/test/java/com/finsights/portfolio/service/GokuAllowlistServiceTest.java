package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.GokuAllowedUser;
import com.finsights.portfolio.dto.GokuAllowedUserResponse;
import com.finsights.portfolio.repository.GokuAllowedUserRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GokuAllowlistServiceTest {

    @Mock GokuAllowedUserRepository repository;

    private GokuAllowlistService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GokuAllowlistService(repository);
        set("seedAllowlist", "ash4691@gmail.com,demo@finsights.local");
        set("defaultModel", "claude-haiku-4-5-20251001");
        set("availableModelsRaw", "claude-opus-5-5,claude-sonnet-5,claude-haiku-4-5-20251001");
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
    void addIsIdempotentForAnAlreadyAllowedEmail() {
        GokuAllowedUser existing = new GokuAllowedUser("someone@gmail.com", "seed");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(existing));

        GokuAllowedUserResponse result = service.add("Someone@Gmail.com", "ash4691@gmail.com");

        assertThat(result.email()).isEqualTo("someone@gmail.com");
        verify(repository, never()).save(any());
    }

    @Test
    void addNormalizesAndSavesANewEmail() {
        when(repository.findByEmailIgnoreCase("new@gmail.com")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GokuAllowedUserResponse result = service.add(" New@Gmail.com ", "ash4691@gmail.com");

        assertThat(result.email()).isEqualTo("new@gmail.com");
        assertThat(result.addedBy()).isEqualTo("ash4691@gmail.com");
    }

    @Test
    void removeNormalizesTheEmailBeforeDeleting() {
        service.remove(" Someone@Gmail.com ");

        verify(repository).deleteByEmailIgnoreCase("someone@gmail.com");
    }

    @Test
    void listMapsRepositoryRowsToResponses() {
        when(repository.findAllByOrderByCreatedAtAsc()).thenReturn(List.of(
                new GokuAllowedUser("a@x.com", "seed"), new GokuAllowedUser("b@x.com", "ash4691@gmail.com")));

        List<GokuAllowedUserResponse> result = service.list();

        assertThat(result).extracting(GokuAllowedUserResponse::email).containsExactly("a@x.com", "b@x.com");
        assertThat(result).extracting(GokuAllowedUserResponse::model)
                .containsExactly("claude-haiku-4-5-20251001", "claude-haiku-4-5-20251001");
    }

    @Test
    void availableModelsReturnsTheConfiguredList() {
        assertThat(service.availableModels()).containsExactly(
                "claude-opus-5-5", "claude-sonnet-5", "claude-haiku-4-5-20251001");
    }

    @Test
    void modelForFallsBackToTheDefaultWhenNoOverrideIsSet() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));

        assertThat(service.modelFor("someone@gmail.com")).isEqualTo("claude-haiku-4-5-20251001");
    }

    @Test
    void modelForFallsBackToTheDefaultForAnUnknownOrBlankEmail() {
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
    void setModelAssignsASupportedModelAndReturnsTheEffectiveValue() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        GokuAllowedUserResponse result = service.setModel(" Someone@Gmail.com ", "claude-sonnet-5");

        assertThat(result.model()).isEqualTo("claude-sonnet-5");
        assertThat(user.getModel()).isEqualTo("claude-sonnet-5");
    }

    @Test
    void setModelRejectsAModelOutsideTheAvailableList() {
        GokuAllowedUser user = new GokuAllowedUser("someone@gmail.com", "seed");
        when(repository.findByEmailIgnoreCase("someone@gmail.com")).thenReturn(Optional.of(user));

        assertThatThrownBy(() -> service.setModel("someone@gmail.com", "gpt-5"))
                .isInstanceOf(ResponseStatusException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void setModelThrowsNotFoundForAnEmailOffTheAllowlist() {
        when(repository.findByEmailIgnoreCase("nobody@gmail.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setModel("nobody@gmail.com", "claude-opus-5-5"))
                .isInstanceOf(ResponseStatusException.class);
    }

    private static GokuAllowedUser argThatEmail(String email) {
        return argThat(u -> u != null && email.equals(u.getEmail()));
    }
}
