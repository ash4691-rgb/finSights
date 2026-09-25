package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.DashboardService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Insights-owned data, wrapped for Goku — calls the same {@code DashboardService} the Overview page uses. */
@Component
class GetDashboardSummaryTool implements GokuTool {
    private final DashboardService dashboard;

    GetDashboardSummaryTool(DashboardService dashboard) { this.dashboard = dashboard; }

    @Override public String name() { return "get_dashboard_summary"; }

    @Override public String description() {
        return "Portfolio-wide summary for the signed-in user: net worth, total assets, total liabilities, "
                + "invested assets, overall profit/loss, and breakdowns by category, broker, and tag.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("currency", Map.of(
                "type", "string",
                "description", "ISO currency code to convert every figure into, e.g. INR or USD. Omit for the account's base currency.")));
    }

    @Override public Object execute(JsonNode input) {
        return dashboard.summary(GokuArgs.text(input, "currency"));
    }
}
