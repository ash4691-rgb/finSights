package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.UserAccount;
import java.lang.reflect.Field;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class GokuAccessServiceTest {

    @Mock CurrentUserService currentUserService;

    private GokuAccessService access(boolean enabled, String allowlist, String apiKey) throws Exception {
        GokuAccessService service = new GokuAccessService(currentUserService);
        set(service, "enabled", enabled);
        set(service, "allowlistRaw", allowlist);
        set(service, "apiKey", apiKey);
        return service;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = GokuAccessService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private void userIs(String email) {
        when(currentUserService.currentUser()).thenReturn(new UserAccount(email, "Someone"));
    }

    @Test
    void availableForAnAllowlistedEmailCaseInsensitively() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com, other@x.com", "sk-test");
        userIs("Ash4691@Gmail.com");

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void notAvailableForAnEmailOutsideTheAllowlist() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone-else@gmail.com");

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void notAvailableWhenTheFeatureIsDisabled() throws Exception {
        GokuAccessService service = access(false, "ash4691@gmail.com", "sk-test");

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void notAvailableWithoutAnApiKeyConfigured() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "");

        assertThat(service.isAvailable()).isFalse();
    }

    @Test
    void requireAvailableThrowsForbiddenWhenNotAvailable() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone-else@gmail.com");

        assertThatThrownBy(service::requireAvailable).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireAvailableDoesNotThrowWhenAvailable() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("ash4691@gmail.com");

        service.requireAvailable();
    }
}
