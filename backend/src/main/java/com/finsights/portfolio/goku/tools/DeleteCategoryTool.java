package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.CategoryService;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** DataFlow-owned write — {@link CategoryService#delete}, which also removes every holding (and their transactions) in it. */
@Component
class DeleteCategoryTool implements GokuWriteTool {
    private final CategoryService categories;

    DeleteCategoryTool(CategoryService categories) { this.categories = categories; }

    @Override public String name() { return "delete_category"; }

    @Override public String description() {
        return "Deletes one of the signed-in user's categories — and with it every holding filed under it and all of "
                + "those holdings' transactions. Irreversible. Tell the user that cascade before proposing it. "
                + "This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of("id", Map.of("type", "string", "description", "The category's id, from get_categories.")),
                "required", List.of("id"));
    }

    @Override public boolean destructive() { return true; }

    @Override public String preview(JsonNode input) {
        CategoryResponse current = categories.get(GokuArgs.requireText(input, "id"), null);
        String cascade = current.holdingCount() == 0 ? "it has no holdings"
                : "this also deletes its " + current.holdingCount() + " holding" + (current.holdingCount() == 1 ? "" : "s")
                        + " and all their transactions";
        return "Delete category \"" + current.name() + "\" — " + cascade + ". This can't be undone.";
    }

    @Override public Object apply(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        categories.delete(id);
        return Map.of("deleted", id);
    }
}
