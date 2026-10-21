package com.finsights.portfolio.dto;

import java.util.List;

/**
 * Result of one statement upload (CAS, a broker holding statement, or a broker transaction
 * statement — one upload box, one endpoint; see {@code StatementImportService}). {@code
 * statementType} is the model's own read of what it found, not something the caller declares up
 * front. {@code warnings} carries anything the model noticed but didn't turn into a row — out of
 * scope, unclear, or (for a transaction) unmatched to an existing holding — so a short result isn't
 * silently under-reported with no explanation.
 */
public record StatementExtractionResponse(
        StatementType statementType,
        List<ExtractedHolding> holdings,
        List<HoldingToRemove> toRemove,
        List<ExtractedTransaction> transactions,
        String statementDate,
        List<String> warnings
) {
    public enum StatementType { HOLDINGS, TRANSACTIONS, MIXED }
}
