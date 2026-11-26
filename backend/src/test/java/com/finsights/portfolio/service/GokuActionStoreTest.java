package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.GokuPendingAction;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class GokuActionStoreTest {

    private final GokuActionStore store = new GokuActionStore();
    private final ObjectMapper json = new ObjectMapper();

    private GokuPendingAction propose(String userId) throws Exception {
        return store.propose(userId, "delete_holding", json.readTree("{\"id\":\"h-1\"}"), "Delete holding", true);
    }

    @Test
    void theProposingUserCanTakeItOnce() throws Exception {
        GokuPendingAction action = propose("user-a");

        assertThat(store.take("user-a", action.id()).toolName()).isEqualTo("delete_holding");
        assertThatThrownBy(() -> store.take("user-a", action.id())).isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void anotherUserCantTakeItAndDoesntDestroyIt() throws Exception {
        GokuPendingAction action = propose("user-a");

        assertThatThrownBy(() -> store.take("user-b", action.id())).isInstanceOf(ResponseStatusException.class);
        assertThat(store.take("user-a", action.id()).input().get("id").asText()).isEqualTo("h-1");
    }

    @Test
    void theStoredInputIsACopy() throws Exception {
        var input = (com.fasterxml.jackson.databind.node.ObjectNode) json.readTree("{\"id\":\"h-1\"}");
        GokuPendingAction action = store.propose("user-a", "delete_holding", input, "Delete holding", true);
        input.put("id", "h-2");

        assertThat(store.take("user-a", action.id()).input().get("id").asText()).isEqualTo("h-1");
    }

    @Test
    void unknownOrNullIdsAreNotFound() {
        assertThatThrownBy(() -> store.take("user-a", "nope")).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> store.take("user-a", null)).isInstanceOf(ResponseStatusException.class);
    }
}
