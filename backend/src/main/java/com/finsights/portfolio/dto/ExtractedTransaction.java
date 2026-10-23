package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.TransactionType;
import java.math.BigDecimal;

/**
 * One transaction a statement-extraction model found in an uploaded broker transaction statement.
 * {@code matchedHoldingId} is null when no existing holding matched; the user then picks the
 * holding in the review form (see {@code StatementImportService.matchAgainstExisting}). The Transactions page pre-fills the ordinary
 * Log Transaction form with one of these and the user reviews and saves it through the exact same
 * path as typing it in by hand — nothing here is written to the database directly.
 */
public record ExtractedTransaction(
        /** YYYY-MM-DD, as instructed in the extraction system prompt. */
        String date,
        TransactionType type,
        String instrumentName,
        String isin,
        /** Null when the statement line doesn't carry a quantity (e.g. INTEREST). */
        BigDecimal quantity,
        /** Null when the statement line doesn't carry an amount (e.g. SPLIT). */
        BigDecimal amount,
        String notes,
        /** The existing holding this row matched, or null if none did; see the class comment. */
        String matchedHoldingId
) { }
