package com.finsights.portfolio.dto;

/**
 * An existing holding tagged from a previous statement import (see {@code
 * StatementImportService.matchHoldings}) whose ISIN didn't turn up in the latest holdings
 * statement — a candidate to delete, applied only when the user clicks Done. Never computed for a
 * transactions-only statement, which doesn't claim to be a full snapshot of what's held.
 */
public record HoldingToRemove(String holdingId, String name, String broker) { }
