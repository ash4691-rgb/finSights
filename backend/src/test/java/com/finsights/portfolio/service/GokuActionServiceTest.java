package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.GokuPendingAction;
import com.finsights.portfolio.goku.GokuToolRegistry;
import com.finsights.portfolio.goku.GokuWriteTool;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GokuActionServiceTest {

    private final GokuActionStore store = new GokuActionStore();
    private final GokuWriteTool tool = mock(GokuWriteTool.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private GokuActionService service;

    @BeforeEach
    void setUp() {
        when(tool.name()).thenReturn("delete_holding");
        service = new GokuActionService(store, new GokuToolRegistry(List.of(tool)), currentUser);
    }

    private void signedInAs(String userId) {
        UserAccount user = mock(UserAccount.class);
        when(user.getId()).thenReturn(userId);
        when(currentUser.currentUser()).thenReturn(user);
    }

    @Test
    void confirmingAppliesTheExactProposedInput() throws Exception {
        GokuPendingAction action = store.propose("user-a", "delete_holding",
                new ObjectMapper().readTree("{\"id\":\"h-1\"}"), "Delete holding \"X\"", true);
        signedInAs("user-a");

        assertThat(service.confirm(action.id()).summary()).isEqualTo("Delete holding \"X\"");
        verify(tool).apply(new ObjectMapper().readTree("{\"id\":\"h-1\"}"));
    }

    @Test
    void anotherUserConfirmingDoesNothing() throws Exception {
        GokuPendingAction action = store.propose("user-a", "delete_holding",
                new ObjectMapper().readTree("{\"id\":\"h-1\"}"), "Delete holding \"X\"", true);
        signedInAs("user-b");

        assertThatThrownBy(() -> service.confirm(action.id())).isInstanceOf(ResponseStatusException.class);
        verify(tool, never()).apply(any());
    }

    @Test
    void dismissingDropsItWithoutApplying() throws Exception {
        GokuPendingAction action = store.propose("user-a", "delete_holding",
                new ObjectMapper().readTree("{\"id\":\"h-1\"}"), "Delete holding \"X\"", true);
        signedInAs("user-a");

        service.dismiss(action.id());

        assertThatThrownBy(() -> service.confirm(action.id())).isInstanceOf(ResponseStatusException.class);
        verify(tool, never()).apply(any());
    }
}
