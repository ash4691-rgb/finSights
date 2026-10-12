package com.finsights.portfolio.dto;

import java.time.Instant;

/** The "Sync now" button's response — mirrors {@code BrokerConnection}'s own sync-status fields
 *  rather than throwing on a fetch failure, since a transient "couldn't reach the broker this
 *  round" is a routine, expected outcome here, not an application error. */
public record SyncNowResponse(boolean synced, Instant lastSyncedAt, String lastSyncError) { }
