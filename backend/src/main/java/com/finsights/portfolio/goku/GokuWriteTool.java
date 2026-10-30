package com.finsights.portfolio.goku;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A Goku tool that changes data. The model can only ever <em>propose</em> one of these: when it
 * calls a write tool, {@code GokuChatService} runs {@link #preview} (validation + ownership checks
 * + a one-line description, writing nothing) and parks the call as a pending action. {@link
 * #apply} runs only from {@code POST /api/goku/actions/{id}/confirm} — the signed-in user's own
 * click on the Confirm button in the chat — never from inside the model loop. That makes "the
 * user explicitly approved this exact change" a property of the code path, not of the prompt.
 */
public interface GokuWriteTool extends GokuTool {

    /**
     * Validates the input (and that anything it references belongs to the signed-in user) and
     * returns a short, human-readable description of exactly what {@link #apply} would do — shown
     * verbatim on the Confirm card. Must not write anything.
     *
     * @throws RuntimeException on bad input or an unknown/foreign id — surfaces to the model as a
     *         tool error, so nothing is ever proposed that can't be applied.
     */
    String preview(JsonNode input);

    /** Performs the change. Only ever called after the user confirmed the matching preview. */
    Object apply(JsonNode input);

    /** True for anything irreversible (a delete) — the Confirm card renders as destructive. */
    default boolean destructive() { return false; }

    @Override
    default Object execute(JsonNode input) {
        throw new UnsupportedOperationException(name() + " is a write tool — it can only be proposed and user-confirmed");
    }
}
