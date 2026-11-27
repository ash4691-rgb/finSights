package com.finsights.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.AllocationResponse;
import com.finsights.portfolio.dto.AllocationTargetsRequest;
import com.finsights.portfolio.dto.GoalRequest;
import com.finsights.portfolio.dto.GoalResponse;
import com.finsights.portfolio.service.AllocationService;
import com.finsights.portfolio.service.GoalService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PlanningControllerTest {
    private final AllocationService allocation = mock(AllocationService.class);
    private final GoalService goals = mock(GoalService.class);
    private final PlanningController controller = new PlanningController(allocation, goals);

    @Test
    void delegatesAllocationCalls() {
        AllocationResponse response = new AllocationResponse("INR", BigDecimal.ZERO, BigDecimal.ZERO, false, List.of());
        when(allocation.allocation("INR")).thenReturn(response);
        when(allocation.saveTargets(Map.of("c", BigDecimal.ONE), "INR")).thenReturn(response);
        assertThat(controller.allocation("INR")).isSameAs(response);
        assertThat(controller.saveTargets(new AllocationTargetsRequest(Map.of("c", BigDecimal.ONE)), "INR")).isSameAs(response);
    }

    @Test
    void delegatesGoalCalls() {
        GoalRequest request = new GoalRequest("G", BigDecimal.TEN, null, null, null, null);
        GoalResponse response = mock(GoalResponse.class);
        when(goals.list()).thenReturn(List.of(response));
        when(goals.create(request)).thenReturn(response);
        when(goals.update("g", request)).thenReturn(response);

        assertThat(controller.goals()).containsExactly(response);
        assertThat(controller.createGoal(request)).isSameAs(response);
        assertThat(controller.updateGoal("g", request)).isSameAs(response);
        controller.deleteGoal("g");
        verify(goals).delete("g");
    }
}
