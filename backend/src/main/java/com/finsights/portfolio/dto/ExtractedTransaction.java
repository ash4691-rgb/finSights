package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.TransactionType;
import java.math.BigDecimal;

/**
 * One transaction a statement-extraction model found in an uploaded broker transaction statement.
 * Only ever surfaced to the user when {@code matchedHoldingId} is set — logging a transaction
 * against a holding that doesn't already exist isn't possible through the ordinary Log Transaction
 * form, so an unmatched row is dropped into the extraction's {@code warnings} instead (see
 * {@code StatementImportService.matchTransactions}). The Transactions page pre-fills the ordinary
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
        /** The existing holding this row's ISIN matched — always set on a row that reaches the
         *  caller; see the class comment. */
        String matchedHoldingId
) { }
