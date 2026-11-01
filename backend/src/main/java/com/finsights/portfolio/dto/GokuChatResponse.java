package com.finsights.portfolio.dto;

import java.util.List;

/** {@code pendingActions} are the writes proposed this turn — nothing in it has been applied yet. */
public record GokuChatResponse(String reply, int queriesRemainingToday, List<GokuPendingAction> pendingActions) {
    public GokuChatResponse {
        pendingActions = pendingActions == null ? List.of() : List.copyOf(pendingActions);
    }
}
