package com.finsights.portfolio.goku;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/**
 * One read-only capability Goku (the portfolio chat assistant) can call. This is the contract
 * Platform owns; each implementation is a thin adapter over a method an owning team's service
 * already exposes (DataFlow's {@code HoldingService}, Insights' {@code DashboardService}, ...) —
 * see {@code goku.tools} for the registered set.
 *
 * <p>There is deliberately no write variant of this interface: a tool can only wrap a
 * {@code list()}/{@code get()}-shaped read, never a {@code save()}/{@code delete()}. That is
 * enforced by convention here, not by the model's judgment.
 */
public interface GokuTool {
    /** Stable, snake_case name the model calls this tool by, e.g. {@code get_holdings}. */
    String name();

    /** What this tool returns and when to call it — shown to the model verbatim. */
    String description();

    /** JSON Schema for this tool's arguments (Anthropic's {@code input_schema}). */
    Map<String, Object> inputSchema();

    /**
     * Runs the tool against the model-supplied arguments. Implementations call straight into an
     * existing, already-user-scoped service (via {@code CurrentUserService}), so a tool can never
     * see another user's data — it has no user id to accept from the model in the first place.
     *
     * @throws RuntimeException on bad input; the orchestrator turns this into a tool-level error
     *         the model sees and can recover from, rather than failing the whole chat turn.
     */
    Object execute(JsonNode input);
}
