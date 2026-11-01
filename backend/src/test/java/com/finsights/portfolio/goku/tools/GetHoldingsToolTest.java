package com.finsights.portfolio.goku.tools;

import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GetHoldingsToolTest {

    @Mock HoldingService holdingService;

    private static HoldingResponse holding(String name, String broker, Set<String> tags, String invested, String current) {
        HoldingResponse h = mock(HoldingResponse.class);
        when(h.name()).thenReturn(name);
        when(h.broker()).thenReturn(broker);
        when(h.tags()).thenReturn(tags);
        when(h.currency()).thenReturn("INR");
        when(h.investedValue()).thenReturn(new BigDecimal(invested));
        when(h.currentValue()).thenReturn(new BigDecimal(current));
        when(h.profitLoss()).thenReturn(new BigDecimal(current).subtract(new BigDecimal(invested)));
        return h;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> totals(Object result) { return (List<Map<String, Object>>) ((Map<String, Object>) result).get("totals"); }

    @Test
    void filtersByBrokerAndTagAndTotalsOnlyTheMatches() {
        HoldingResponse a = holding("Reliance", "Zerodha", Set.of("core"), "100", "150");
        HoldingResponse b = holding("Infosys", "zerodha", Set.of("Core", "it"), "200", "180");
        HoldingResponse c = holding("Gold", "Groww", Set.of("core"), "50", "60");
        when(holdingService.list("INR")).thenReturn(List.of(a, b, c));

        Object result = new GetHoldingsTool(holdingService).execute(json("{\"currency\":\"INR\",\"broker\":\"ZERODHA\",\"tag\":\"core\"}"));

        assertThat(((Map<?, ?>) result).get("holdings")).isEqualTo(List.of(a, b));
        assertThat(totals(result)).singleElement().satisfies(row -> {
            assertThat((BigDecimal) row.get("investedValue")).isEqualByComparingTo("300");
            assertThat((BigDecimal) row.get("currentValue")).isEqualByComparingTo("330");
            assertThat((BigDecimal) row.get("profitLoss")).isEqualByComparingTo("30");
        });
    }

    @Test
    void withACategoryFilterDelegatesToListByCategory() {
        when(holdingService.listByCategory("c-1", "INR")).thenReturn(List.of());

        new GetHoldingsTool(holdingService).execute(json("{\"category_id\":\"c-1\",\"currency\":\"INR\"}"));

        verify(holdingService).listByCategory("c-1", "INR");
    }

    @Test
    void capsTheListedRowsButTotalsEveryMatch() {
        List<HoldingResponse> many = new ArrayList<>();
        for (int i = 0; i < GetHoldingsTool.MAX_ROWS + 5; i++) many.add(holding("H" + i, "Zerodha", Set.of(), "1", "2"));
        when(holdingService.list(null)).thenReturn(many);

        Map<?, ?> result = (Map<?, ?>) new GetHoldingsTool(holdingService).execute(json("{}"));

        assertThat((List<?>) result.get("holdings")).hasSize(GetHoldingsTool.MAX_ROWS);
        assertThat(result.get("truncated")).isEqualTo(true);
        assertThat(result.get("count")).isEqualTo(GetHoldingsTool.MAX_ROWS + 5);
        assertThat((BigDecimal) totals(result).get(0).get("currentValue")).isEqualByComparingTo(String.valueOf(2 * (GetHoldingsTool.MAX_ROWS + 5)));
    }
}
