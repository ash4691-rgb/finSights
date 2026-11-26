package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.HoldingService;
import jakarta.validation.Validator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * DataFlow-owned write — {@link HoldingService#update}, patch-style (see {@link GokuArgs#overlay}):
 * the current holding is read in its native currency (the same unconverted read the edit form
 * resaves from), so an untouched field is written back exactly as it was.
 */
@Component
class UpdateHoldingTool implements GokuWriteTool {
    private final HoldingService holdings;
    private final ObjectMapper json;
    private final Validator validator;

    UpdateHoldingTool(HoldingService holdings, ObjectMapper sharedJson, Validator validator) {
        this.holdings = holdings;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "update_holding"; }

    @Override public String description() {
        return "Changes an existing holding of the signed-in user (rename, move category, fix broker, values, rate, "
                + "tags...). Send its id plus only the fields to change; everything else keeps its current value. "
                + "This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        Map<String, Object> schema = new HashMap<>(CreateHoldingTool.FIELDS);
        schema.put("id", Map.of("type", "string", "description", "The holding's id, from get_holdings."));
        return Map.of("type", "object", "properties", schema, "required", List.of("id"));
    }

    @Override public String preview(JsonNode input) {
        HoldingResponse current = holdings.get(GokuArgs.requireText(input, "id"));
        GokuArgs.overlay(json, validator, current, input, HoldingRequest.class, "id");
        return "Update holding \"" + current.name() + "\" (" + current.broker() + "): " + GokuArgs.describeChanges(input, "id");
    }

    @Override public Object apply(JsonNode input) {
        String id = GokuArgs.requireText(input, "id");
        HoldingRequest request = GokuArgs.overlay(json, validator, holdings.get(id), input, HoldingRequest.class, "id");
        return holdings.update(id, request);
    }
}
