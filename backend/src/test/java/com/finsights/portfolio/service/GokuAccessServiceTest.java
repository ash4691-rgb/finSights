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
    @Mock GokuAllowlistService allowlistService;

    private GokuAccessService access(boolean enabled, String adminAllowlist, String apiKey) throws Exception {
        GokuAccessService service = new GokuAccessService(currentUserService, allowlistService);
        set(service, "enabled", enabled);
        set(service, "adminAllowlistRaw", adminAllowlist);
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
    void availableWhenTheAllowlistServiceSaysYes() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone@gmail.com");
        when(allowlistService.isAllowed("someone@gmail.com")).thenReturn(true);

        assertThat(service.isAvailable()).isTrue();
    }

    @Test
    void notAvailableWhenTheAllowlistServiceSaysNo() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone@gmail.com");
        when(allowlistService.isAllowed("someone@gmail.com")).thenReturn(false);

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
    void adminAllowlistIsSeparateFromTheChatAllowlist() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com, Other@Example.com", "sk-test");
        userIs("other@example.com");

        assertThat(service.isAdmin()).isTrue();
    }

    @Test
    void notAdminForAnEmailOutsideTheAdminAllowlist() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone-else@gmail.com");

        assertThat(service.isAdmin()).isFalse();
    }

    @Test
    void requireAvailableThrowsForbiddenWhenNotAvailable() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone@gmail.com");
        when(allowlistService.isAllowed("someone@gmail.com")).thenReturn(false);

        assertThatThrownBy(service::requireAvailable).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireAdminThrowsForbiddenWhenNotAdmin() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("someone-else@gmail.com");

        assertThatThrownBy(service::requireAdmin).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void requireAdminDoesNotThrowForAnAdmin() throws Exception {
        GokuAccessService service = access(true, "ash4691@gmail.com", "sk-test");
        userIs("ash4691@gmail.com");

        service.requireAdmin();
    }
}
