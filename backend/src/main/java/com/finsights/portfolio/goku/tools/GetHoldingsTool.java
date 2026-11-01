package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.HoldingService;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned data, wrapped for Goku. Calls {@link HoldingService#list(String)} /
 * {@link HoldingService#listByCategory(String, String)} — the same safe-read path the Holdings
 * page uses, where a single bad record degrades to a flagged placeholder (see
 * {@code HoldingService#toResponse}) instead of failing the whole call. The broker/tag/name
 * filters and the per-currency totals are plain projections over that list — no valuation logic
 * is re-done here — and the totals exist so the model never has to add up rows itself.
 */
@Component
class GetHoldingsTool implements GokuTool {
    static final int MAX_ROWS = 150;

    private final HoldingService holdings;

    GetHoldingsTool(HoldingService holdings) { this.holdings = holdings; }

    @Override public String name() { return "get_holdings"; }

    @Override public String description() {
        return "Lists the signed-in user's holdings (assets and liabilities), each with its id, category, valuation "
                + "method, ticker, broker, tags, invested/current value, profit/loss, and quantity, plus `totals` per "
                + "currency computed over every match — quote those totals rather than adding rows up yourself. "
                + "Filter by category, broker, tag, and/or name. At most " + MAX_ROWS + " rows are listed "
                + "(`truncated` says so); totals always cover every match. A holding with a data problem is flagged via "
                + "dataIssue rather than dropped.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "currency", Map.of("type", "string",
                        "description", "ISO currency code to convert values into, e.g. INR. Pass the user's base currency for one combined total."),
                "category_id", Map.of("type", "string", "description", "Only holdings in this category id, from get_categories."),
                "broker", Map.of("type", "string", "description", "Only holdings at this broker (case-insensitive exact match)."),
                "tag", Map.of("type", "string", "description", "Only holdings carrying this tag (case-insensitive)."),
                "name", Map.of("type", "string", "description", "Only holdings whose name or ticker contains this text.")));
    }

    @Override public Object execute(JsonNode input) {
        String currency = GokuArgs.text(input, "currency");
        String categoryId = GokuArgs.text(input, "category_id");
        String broker = GokuArgs.text(input, "broker");
        String tag = GokuArgs.text(input, "tag");
        String name = lower(GokuArgs.text(input, "name"));
        List<HoldingResponse> matches = (categoryId == null ? holdings.list(currency) : holdings.listByCategory(categoryId, currency))
                .stream()
                .filter(h -> broker == null || broker.equalsIgnoreCase(h.broker()))
                .filter(h -> tag == null || (h.tags() != null && h.tags().stream().anyMatch(tag::equalsIgnoreCase)))
                .filter(h -> name == null || lower(h.name()).contains(name) || (h.tickerSymbol() != null && lower(h.tickerSymbol()).contains(name)))
                .toList();

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", matches.size());
        result.put("totals", totals(matches));
        result.put("holdings", matches.size() > MAX_ROWS ? matches.subList(0, MAX_ROWS) : matches);
        result.put("truncated", matches.size() > MAX_ROWS);
        return result;
    }

    private static List<Map<String, Object>> totals(List<HoldingResponse> matches) {
        return matches.stream().collect(Collectors.groupingBy(h -> String.valueOf(h.currency()), LinkedHashMap::new, Collectors.toList()))
                .entrySet().stream().map(e -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("currency", e.getKey());
                    row.put("holdings", e.getValue().size());
                    row.put("investedValue", sum(e.getValue(), HoldingResponse::investedValue));
                    row.put("currentValue", sum(e.getValue(), HoldingResponse::currentValue));
                    row.put("profitLoss", sum(e.getValue(), HoldingResponse::profitLoss));
                    return row;
                }).toList();
    }

    private static BigDecimal sum(List<HoldingResponse> rows, java.util.function.Function<HoldingResponse, BigDecimal> field) {
        return rows.stream().map(field).filter(v -> v != null).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static String lower(String value) { return value == null ? null : value.toLowerCase(Locale.ROOT); }
}
