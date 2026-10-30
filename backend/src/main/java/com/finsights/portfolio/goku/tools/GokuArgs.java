package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.Set;
import java.util.stream.Collectors;

/** Pulls arguments out of a tool call's JSON input. */
final class GokuArgs {
    private GokuArgs() {}

    static String text(JsonNode input, String field) {
        if (input == null) return null;
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Deserializes a tool call's input into a request DTO and runs it through the same
     * {@code jakarta.validation} constraints its REST controller would via {@code @Valid} — a
     * write tool calls its service directly, bypassing that annotation, so without this a model
     * mistake (a missing name, an invalid enum value) would hit the service as a raw null/garbage
     * value instead of a clear error the model can see and correct. The violation message becomes
     * the tool's error result, which {@code GokuChatService} feeds back to the model as {@code
     * is_error: true} — the same recovery path as any other tool failure.
     */
    static <T> T parseAndValidate(ObjectMapper json, Validator validator, JsonNode input, Class<T> type) {
        T value;
        try {
            value = json.treeToValue(input, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid input: " + e.getMessage(), e);
        }
        Set<ConstraintViolation<T>> violations = validator.validate(value);
        if (!violations.isEmpty()) {
            String message = violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; "));
            throw new IllegalArgumentException(message);
        }
        return value;
    }
}
