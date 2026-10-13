package com.finsights.portfolio.dto;

import java.time.LocalDate;

/**
 * One Action Centre row the user snoozed, reconstructed from its {@link ActionItemResponse#key()
 * dismissal key} for display in Signals' Radar — so a deferred item can be seen (and un-snoozed)
 * instead of silently waiting to reappear on {@code deferredUntil}.
 */
public record DeferredActionResponse(
        String key,
        String kind,
        String label,
        String holdingName,
        LocalDate deferredUntil
) { }
