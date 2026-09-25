package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.service.HoldingService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetHoldingsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock HoldingService holdingService;

    @Test
    void withoutACategoryFilterListsEveryHolding() throws Exception {
        GetHoldingsTool tool = new GetHoldingsTool(holdingService);
        JsonNode input = mapper.readTree("{\"currency\":\"USD\"}");
        when(holdingService.list("USD")).thenReturn(List.of());

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(List.of());
        verify(holdingService).list("USD");
        verifyNoMoreInteractions(holdingService);
    }

    @Test
    void withACategoryFilterDelegatesToListByCategory() throws Exception {
        GetHoldingsTool tool = new GetHoldingsTool(holdingService);
        JsonNode input = mapper.readTree("{\"category_id\":\"c-1\",\"currency\":\"INR\"}");
        when(holdingService.listByCategory("c-1", "INR")).thenReturn(List.of());

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(List.of());
        verify(holdingService).listByCategory("c-1", "INR");
        verifyNoMoreInteractions(holdingService);
    }
}
