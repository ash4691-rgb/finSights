package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.HoldingService;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned data, wrapped for Goku. Calls {@link HoldingService#list(String)} /
 * {@link HoldingService#listByCategory(String, String)} — the same safe-read path the Holdings
 * page uses, where a single bad record degrades to a flagged placeholder (see
 * {@code HoldingService#toResponse}) instead of failing the whole call. Goku's tool set never
 * has to special-case that; it inherits it for free by calling the same method.
 */
@Component
class GetHoldingsTool implements GokuTool {
    private final HoldingService holdings;

    GetHoldingsTool(HoldingService holdings) { this.holdings = holdings; }

    @Override public String name() { return "get_holdings"; }

    @Override public String description() {
        return "Lists the signed-in user's holdings (assets and liabilities), each with its category, valuation "
                + "method, ticker (if market-linked), broker, invested/current value, profit/loss, and quantity. "
                + "A holding with a data problem still comes back, flagged via dataIssue, rather than being dropped.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "currency", Map.of("type", "string",
                        "description", "ISO currency code to convert values into, e.g. INR or USD. Omit for each holding's native currency."),
                "category_id", Map.of("type", "string",
                        "description", "Only holdings in this category id, as returned by get_categories. Omit to list every holding.")));
    }

    @Override public Object execute(JsonNode input) {
        String currency = GokuArgs.text(input, "currency");
        String categoryId = GokuArgs.text(input, "category_id");
        return categoryId == null ? holdings.list(currency) : holdings.listByCategory(categoryId, currency);
    }
}
