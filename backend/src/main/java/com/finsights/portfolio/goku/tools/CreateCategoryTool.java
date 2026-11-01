package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.CategoryService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned write, wrapped for Goku. Calls {@link CategoryService#create}, the same path
 * the Categories page's "Add category" form uses — the new category belongs to whichever user is
 * signed in to this chat (CategoryService resolves that itself via CurrentUserService), the same
 * as every read tool.
 */
@Component
class CreateCategoryTool implements GokuWriteTool {
    private final CategoryService categories;
    private final ObjectMapper json;
    private final Validator validator;

    CreateCategoryTool(CategoryService categories, ObjectMapper sharedJson, Validator validator) {
        this.categories = categories;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "create_category"; }

    @Override public String description() {
        return "Creates a new category — a named bucket of holdings, e.g. \"US Stocks\" or \"Home Loan\" — "
                + "for the signed-in user. This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object",
                "properties", Map.of(
                        "name", Map.of("type", "string", "description", "1-128 characters."),
                        "kind", Map.of("type", "string", "enum", List.of("ASSET", "LIABILITY"),
                                "description", "ASSET for something owned (stocks, funds, property); LIABILITY for something owed (a loan)."),
                        "description", Map.of("type", "string", "description", "Optional, up to 1024 characters."),
                        "realEstate", Map.of("type", "boolean",
                                "description", "True only for a real-estate category, whose holdings use area+unit instead of quantity. Defaults to false.")),
                "required", List.of("name", "kind"));
    }

    @Override public String preview(JsonNode input) {
        CategoryRequest request = GokuArgs.parseAndValidate(json, validator, input, CategoryRequest.class);
        return "Create " + request.kind().name().toLowerCase() + " category \"" + request.name() + "\"";
    }

    @Override public Object apply(JsonNode input) {
        return categories.create(GokuArgs.parseAndValidate(json, validator, input, CategoryRequest.class));
    }
}
