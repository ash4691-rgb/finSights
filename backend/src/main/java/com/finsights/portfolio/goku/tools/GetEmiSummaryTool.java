package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.EmiService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Insights-owned data, wrapped for Goku — calls the same {@code EmiService} the Action Centre's EMI rows use. */
@Component
class GetEmiSummaryTool implements GokuTool {
    private final EmiService emis;

    GetEmiSummaryTool(EmiService emis) { this.emis = emis; }

    @Override public String name() { return "get_emi_summary"; }

    @Override public String description() {
        return "EMI / loan instalments currently due, overdue, or due within the next few days for the "
                + "signed-in user's liabilities, each with the holding it belongs to, the amount, and the due date.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override public Object execute(JsonNode input) {
        return emis.dueItems();
    }
}
