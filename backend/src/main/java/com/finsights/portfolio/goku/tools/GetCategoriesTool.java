package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.CategoryService;
import java.util.Map;
import org.springframework.stereotype.Component;

/** DataFlow-owned data, wrapped for Goku — calls the same {@code CategoryService} the Categories page uses. */
@Component
class GetCategoriesTool implements GokuTool {
    private final CategoryService categories;

    GetCategoriesTool(CategoryService categories) { this.categories = categories; }

    @Override public String name() { return "get_categories"; }

    @Override public String description() {
        return "Lists the signed-in user's asset and liability categories, each with invested value, current "
                + "value, profit/loss, weightage percent, liquid/NPA amounts, and holding count.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of("currency", Map.of(
                "type", "string",
                "description", "ISO currency code to convert totals into, e.g. INR or USD. Omit for each category's native currency mix.")));
    }

    @Override public Object execute(JsonNode input) {
        return categories.list(GokuArgs.text(input, "currency"));
    }
}
