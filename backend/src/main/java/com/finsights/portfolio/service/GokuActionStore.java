package com.finsights.portfolio.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.dto.GokuPendingAction;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Write-tool calls Goku has proposed but the user hasn't confirmed yet. In memory on purpose:
 * an unconfirmed proposal is disposable (a restart just means "ask Goku again"), and nothing
 * here is ever applied without a matching confirm from the same user.
 *
 * <p>Same ownership rule as {@link GokuUploadStore}: {@link #take} peeks, checks the caller owns
 * the action, and only then removes it — so a guessed id from another account 404s with no side
 * effect on the real owner's pending action.
 */
@Service
public class GokuActionStore {
    private static final Duration TTL = Duration.ofMinutes(30);
    private static final int MAX_PER_USER = 100;

    public record Stored(String userId, String toolName, JsonNode input, GokuPendingAction view, Instant expiresAt) {}

    private final Map<String, Stored> actions = new ConcurrentHashMap<>();

    public GokuPendingAction propose(String userId, String toolName, JsonNode input, String summary, boolean destructive) {
        purgeExpired();
        long open = actions.values().stream().filter(a -> a.userId().equals(userId)).count();
        if (open >= MAX_PER_USER) {
            throw new IllegalStateException("Too many unconfirmed changes are waiting — confirm or dismiss some first.");
        }
        String id = UUID.randomUUID().toString();
        GokuPendingAction view = new GokuPendingAction(id, toolName, summary, destructive);
        actions.put(id, new Stored(userId, toolName, input.deepCopy(), view, Instant.now().plus(TTL)));
        return view;
    }

    public Stored take(String userId, String actionId) {
        Stored action = actionId == null ? null : actions.get(actionId);
        if (action == null || action.expiresAt().isBefore(Instant.now()) || !action.userId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "That change has expired — ask Goku again.");
        }
        actions.remove(actionId);
        return action;
    }

    private void purgeExpired() {
        Instant now = Instant.now();
        actions.values().removeIf(a -> a.expiresAt().isBefore(now));
    }
}
