package com.finsights.portfolio.dto;

import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import java.util.List;

/**
 * What the user confirmed after the first read of a statement: its type and the broker it belongs
 * to, alongside the rows that read produced. Sent back to {@code /api/statements/reconcile} so the
 * rows are matched against that one broker's holdings — the file itself is never uploaded twice.
 */
public record StatementReconcileRequest(
        StatementType statementType,
        String broker,
        String statementDate,
        List<ExtractedHolding> holdings,
        List<ExtractedTransaction> transactions,
        List<String> warnings
) {
    public StatementReconcileRequest {
        holdings = holdings == null ? List.of() : List.copyOf(holdings);
        transactions = transactions == null ? List.of() : List.copyOf(transactions);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
