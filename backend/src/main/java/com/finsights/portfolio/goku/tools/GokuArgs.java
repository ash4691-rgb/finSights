package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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

    /**
     * Patch-style update: projects {@code current} (a response DTO) onto {@code type}'s record
     * components, overlays only the fields present in {@code changes} (minus {@code ignore}, e.g.
     * the {@code id} key), and validates the result. The model never has to resend a whole
     * record — a field it leaves out keeps its current value instead of being wiped to null.
     */
    static <T extends Record> T overlay(ObjectMapper json, Validator validator, Object current, JsonNode changes,
                                        Class<T> type, String... ignore) {
        ObjectNode base = json.valueToTree(current);
        ObjectNode merged = json.createObjectNode();
        for (RecordComponent component : type.getRecordComponents()) {
            String field = component.getName();
            if (base.has(field)) merged.set(field, base.get(field));
        }
        Set<String> skip = Set.of(ignore);
        Set<String> allowed = Arrays.stream(type.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
        changes.fieldNames().forEachRemaining(field -> {
            if (skip.contains(field)) return;
            if (!allowed.contains(field)) throw new IllegalArgumentException("Unknown field \"" + field + "\"");
            merged.set(field, changes.get(field));
        });
        return parseAndValidate(json, validator, merged, type);
    }

    /** "name → "US Equities", broker → "Zerodha"" — the changed fields as the Confirm card shows them. */
    static String describeChanges(JsonNode changes, String... ignore) {
        Set<String> skip = Set.of(ignore);
        List<String> parts = new ArrayList<>();
        changes.fields().forEachRemaining(e -> {
            if (!skip.contains(e.getKey())) parts.add(e.getKey() + " → " + (e.getValue().isTextual() ? "\"" + e.getValue().asText() + "\"" : e.getValue().toString()));
        });
        if (parts.isEmpty()) throw new IllegalArgumentException("No changes given — include at least one field to update");
        return String.join(", ", parts);
    }

    static String requireText(JsonNode input, String field) {
        String value = text(input, field);
        if (value == null) throw new IllegalArgumentException(field + " is required");
        return value;
    }
}
