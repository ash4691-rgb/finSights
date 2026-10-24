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
        List<String> warnings,
        /** The broker/platform the statement itself names, or null when it names none — a suggestion
         *  the user confirms (or replaces) before the statement is matched against their holdings. */
        String broker
) {
    public StatementExtractionResponse(StatementType statementType, List<ExtractedHolding> holdings,
                                       List<HoldingToRemove> toRemove, List<ExtractedTransaction> transactions,
                                       String statementDate, List<String> warnings) {
        this(statementType, holdings, toRemove, transactions, statementDate, warnings, null);
    }

    // Defensive copies so a caller handed this response can't mutate state shared with whoever
    // built it (or be handed a list they can mutate back into it) — the lists always come from
    // List.of()/.toList() today, but the accessor's signature doesn't promise that.
    public StatementExtractionResponse {
        holdings = List.copyOf(holdings);
        toRemove = List.copyOf(toRemove);
        transactions = List.copyOf(transactions);
        warnings = List.copyOf(warnings);
    }

    public enum StatementType { HOLDINGS, TRANSACTIONS, MIXED }
}
