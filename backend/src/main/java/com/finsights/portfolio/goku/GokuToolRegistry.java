package com.finsights.portfolio.goku;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Every {@link GokuTool} bean in the app, collected by Spring and exposed both for dispatch
 * (by name, once the model asks to call one) and as the "tools" array the orchestrator sends
 * to the Claude API. Adding a tool is just registering another {@code GokuTool} bean — nothing
 * here needs to change when DataFlow or Insights adds one.
 */
@Service
public class GokuToolRegistry {
    private final Map<String, GokuTool> byName;

    public GokuToolRegistry(List<GokuTool> tools) {
        Map<String, GokuTool> map = new LinkedHashMap<>();
        for (GokuTool tool : tools) map.put(tool.name(), tool);
        this.byName = Map.copyOf(map);
    }

    public Optional<GokuTool> find(String name) {
        return Optional.ofNullable(byName.get(name));
    }

    /** The Anthropic Messages API's "tools" request field. */
    public List<Map<String, Object>> definitions() {
        return byName.values().stream()
                .map(tool -> Map.<String, Object>of(
                        "name", tool.name(),
                        "description", tool.description(),
                        "input_schema", tool.inputSchema()))
                .toList();
    }
}
