package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.TopMoversService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Insights-owned data, wrapped for Goku — calls the same {@code TopMoversService} that backs Hot Picks. */
@Component
class GetHotPicksTool implements GokuTool {
    private final TopMoversService topMovers;

    GetHotPicksTool(TopMoversService topMovers) { this.topMovers = topMovers; }

    @Override public String name() { return "get_hot_picks"; }

    @Override public String description() {
        return "Holdings and watchlist items whose price has moved past the user's configured threshold over some "
                + "period (daily/weekly/monthly/quarterly/half-yearly/yearly), in either direction — the app's "
                + "\"Hot Picks\" list. Each entry names the period and the percent moved.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("currency", Map.of(
                "type", "string",
                "description", "ISO currency code to convert current values into, e.g. INR or USD. Omit for each item's native currency.")));
    }

    @Override public Object execute(JsonNode input) {
        return topMovers.topMovers(GokuArgs.text(input, "currency"));
    }
}
