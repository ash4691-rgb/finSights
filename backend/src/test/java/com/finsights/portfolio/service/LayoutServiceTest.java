package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.finsights.portfolio.domain.DashboardLayout;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.DashboardLayoutRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class LayoutServiceTest {

    @Mock DashboardLayoutRepository layouts;
    @Mock CurrentUserService currentUserService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final UserAccount user = new UserAccount("demo@finsights.local", "Demo");
    private final List<DashboardLayout> stored = new ArrayList<>();
    private LayoutService service;

    @BeforeEach
    void setUp() {
        service = new LayoutService(layouts, currentUserService, objectMapper);
    }

    @Test
    void saveThenAllRoundTripsTheJson() {
        when(currentUserService.currentUser()).thenReturn(user);
        stubUpsert();
        ObjectNode node = objectMapper.createObjectNode().put("order", "widget-1");

        service.save("brokers", node);
        when(layouts.findByUser_Id(user.getId())).thenReturn(stored);

        JsonNode roundTripped = service.all().get("brokers").config();
        assertThat(roundTripped).isEqualTo(node);
    }

    @Test
    void secondSaveOnSamePageUpdatesTheSameRow() {
        when(currentUserService.currentUser()).thenReturn(user);
        stubUpsert();

        service.save("brokers", objectMapper.createObjectNode().put("v", 1));
        DashboardLayout firstRow = stored.get(0);
        service.save("brokers", objectMapper.createObjectNode().put("v", 2));

        assertThat(stored).hasSize(1);
        assertThat(stored.get(0)).isSameAs(firstRow);
        assertThat(stored.get(0).getConfig()).contains("\"v\":2");
    }

    @Test
    void saveWithUnknownPageIsRejected() {
        assertThatThrownBy(() -> service.save("bogus", objectMapper.createObjectNode()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("400");
    }

    @Test
    void saveWithOversizedConfigIsRejected() {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("blob", "x".repeat(70_000));

        assertThatThrownBy(() -> service.save("brokers", node))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("413");
    }

    @Test
    void allOnlyReturnsTheCurrentUsersRows() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(layouts.findByUser_Id(user.getId())).thenReturn(List.of());
        assertThat(service.all()).isEmpty();
    }

    private void stubUpsert() {
        when(layouts.findByUser_IdAndPage(any(), any())).thenAnswer(inv ->
                stored.stream().filter(r -> r.getPage().equals(inv.getArgument(1))).findFirst());
        when(layouts.save(any())).thenAnswer(inv -> {
            DashboardLayout row = inv.getArgument(0);
            if (!stored.contains(row)) stored.add(row);
            return row;
        });
    }
}
