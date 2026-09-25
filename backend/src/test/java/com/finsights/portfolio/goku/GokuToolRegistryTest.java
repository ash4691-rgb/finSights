package com.finsights.portfolio.goku;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class GokuToolRegistryTest {

    private static GokuTool tool(String name) {
        return new GokuTool() {
            @Override public String name() { return name; }
            @Override public String description() { return "does " + name; }
            @Override public Map<String, Object> inputSchema() { return Map.of("type", "object", "properties", Map.of()); }
            @Override public Object execute(JsonNode input) { return name + "-result"; }
        };
    }

    @Test
    void findsARegisteredToolByName() {
        GokuToolRegistry registry = new GokuToolRegistry(List.of(tool("get_a"), tool("get_b")));

        assertThat(registry.find("get_b")).isPresent();
        assertThat(registry.find("get_b").get().execute(null)).isEqualTo("get_b-result");
    }

    @Test
    void missingToolIsAbsent() {
        GokuToolRegistry registry = new GokuToolRegistry(List.of(tool("get_a")));

        assertThat(registry.find("no_such_tool")).isEmpty();
    }

    @Test
    void definitionsCarryNameDescriptionAndInputSchemaForEveryTool() {
        GokuToolRegistry registry = new GokuToolRegistry(List.of(tool("get_a"), tool("get_b")));

        List<Map<String, Object>> definitions = registry.definitions();

        assertThat(definitions).hasSize(2);
        assertThat(definitions).allSatisfy(def -> {
            assertThat(def).containsKeys("name", "description", "input_schema");
        });
        assertThat(definitions.stream().map(d -> d.get("name"))).containsExactlyInAnyOrder("get_a", "get_b");
    }
}
