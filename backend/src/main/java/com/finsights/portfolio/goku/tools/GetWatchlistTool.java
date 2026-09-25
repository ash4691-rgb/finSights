package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.WatchlistService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** Insights-owned data, wrapped for Goku — calls the same {@code WatchlistService} the Watchlist page uses. */
@Component
class GetWatchlistTool implements GokuTool {
    private final WatchlistService watchlist;

    GetWatchlistTool(WatchlistService watchlist) { this.watchlist = watchlist; }

    @Override public String name() { return "get_watchlist"; }

    @Override public String description() {
        return "The signed-in user's watchlist — market-linked tickers being tracked but not held — with each "
                + "item's last recorded price, currency, and when it was last updated.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("currency", Map.of(
                "type", "string",
                "description", "ISO currency code to convert prices into, e.g. INR or USD. Omit for each item's native currency.")));
    }

    @Override public Object execute(JsonNode input) {
        return watchlist.list(GokuArgs.text(input, "currency"));
    }
}
