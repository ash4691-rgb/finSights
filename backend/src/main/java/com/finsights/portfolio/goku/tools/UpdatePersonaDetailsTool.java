package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.PersonaDetailsRequest;
import com.finsights.portfolio.dto.PersonaResponse;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.PersonaService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/** Platform-owned write — {@link PersonaService#updateDetails}, patch-style. Never touches the risk profile. */
@Component
class UpdatePersonaDetailsTool implements GokuWriteTool {
    private final PersonaService persona;
    private final ObjectMapper json;
    private final Validator validator;

    UpdatePersonaDetailsTool(PersonaService persona, ObjectMapper sharedJson, Validator validator) {
        this.persona = persona;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "update_persona_details"; }

    @Override public String description() {
        return "Changes the signed-in user's investor-profile details (age, occupation, salary range, portfolio size, "
                + "investing tenure). Send only the fields to change. Doesn't change the risk profile — use "
                + "update_risk_profile for that. This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "age", Map.of("type", "integer"),
                "occupation", Map.of("type", "string"),
                "salaryRange", Map.of("type", "string", "enum",
                        List.of("UNDER_5L", "L5_TO_10L", "L10_TO_25L", "L25_TO_50L", "ABOVE_50L", "PREFER_NOT_TO_SAY")),
                "portfolioSize", Map.of("type", "string", "enum",
                        List.of("UNDER_1L", "L1_TO_10L", "L10_TO_50L", "L50_TO_2CR", "ABOVE_2CR", "PREFER_NOT_TO_SAY")),
                "investingTenure", Map.of("type", "string", "enum",
                        List.of("UNDER_1_YEAR", "ONE_TO_3_YEARS", "THREE_TO_10_YEARS", "OVER_10_YEARS"))));
    }

    @Override public String preview(JsonNode input) {
        GokuArgs.overlay(json, validator, current(), input, PersonaDetailsRequest.class);
        return "Update your investor profile: " + GokuArgs.describeChanges(input);
    }

    @Override public Object apply(JsonNode input) {
        return persona.updateDetails(GokuArgs.overlay(json, validator, current(), input, PersonaDetailsRequest.class));
    }

    private PersonaResponse current() {
        return persona.current().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Set up your investor profile from Settings first"));
    }
}
