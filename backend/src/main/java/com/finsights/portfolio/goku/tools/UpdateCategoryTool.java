package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.CategoryService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/** DataFlow-owned write — {@link CategoryService#update}, patch-style (see {@link GokuArgs#overlay}). */
@Component
class UpdateCategoryTool implements GokuWriteTool {
    private final CategoryService categories;
    private final ObjectMapper json;
    private final Validator validator;

    UpdateCategoryTool(CategoryService categories, ObjectMapper sharedJson, Validator validator) {
        this.categories = categories;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "update_category"; }

    @Override public String description() {
        return "Changes an existing category of the signed-in user. Send its id plus only the fields to change; "
                + "everything else keeps its current value. This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "id", Map.of("type", "string", "description", "The category's id, from get_categories."),
                        "name", Map.of("type", "string"),
                        "kind", Map.of("type", "string", "enum", List.of("ASSET", "LIABILITY")),
                        "description", Map.of("type", "string"),
                        "realEstate", Map.of("type", "boolean")),
                "required", List.of("id"));
    }

    @Override public String preview(JsonNode input) {
        CategoryResponse current = categories.get(GokuArgs.requireText(input, "id"), null);
        GokuArgs.overlay(json, validator, current, input, CategoryRequest.class, "id");
        return "Update category \"" + current.name() + "\": " + GokuArgs.describeChanges(input, "id");
    }

    @Override public Object apply(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        CategoryRequest request = GokuArgs.overlay(json, validator, categories.get(id, null), input, CategoryRequest.class, "id");
        return categories.update(id, request);
    }
}
