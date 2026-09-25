package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;

/** Pulls optional string arguments out of a tool call's JSON input, trimmed and null-if-blank. */
final class GokuArgs {
    private GokuArgs() {}

    static String text(JsonNode input, String field) {
        if (input == null) return null;
        JsonNode node = input.get(field);
        if (node == null || node.isNull()) return null;
        String value = node.asText(null);
        return value == null || value.isBlank() ? null : value.trim();
    }
}
