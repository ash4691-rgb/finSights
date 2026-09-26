package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
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

@ExtendWith(MockitoExtension.class)
class GokuAllowlistServiceTest {

    @Mock GokuAllowedUserRepository repository;

    private GokuAllowlistService service;

    @BeforeEach
    void setUp() throws Exception {
        service = new GokuAllowlistService(repository);
        Field field = GokuAllowlistService.class.getDeclaredField("seedAllowlist");
        field.setAccessible(true);
        field.set(service, "ash4691@gmail.com,demo@finsights.local");
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
    }

    private static GokuAllowedUser argThatEmail(String email) {
        return argThat(u -> u != null && email.equals(u.getEmail()));
    }
}
