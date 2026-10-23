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
        String matchedHoldingId,
        /** What kind of instrument this line is; null on rows built before kinds existed (treated as a stock). */
        InstrumentKind kind,
        /** The symbol the statement prints for this line (a coin's "BTC"), if it prints one. */
        String symbol,
        /** Best-effort ticker for an instrument the user doesn't hold yet at this broker, so a holding
         *  created from its first BUY can be market-valued; null for a matched row or when unsure. */
        String tickerSymbol,
        /** The institution this line went through (broker or fund house) when the statement names one per line; see {@code ExtractedHolding}. */
        String account
) {
    /** The shape every row had before the account existed. */
    public ExtractedTransaction(String date, TransactionType type, String instrumentName, String isin,
                                BigDecimal quantity, BigDecimal amount, String notes, String matchedHoldingId,
                                InstrumentKind kind, String symbol, String tickerSymbol) {
        this(date, type, instrumentName, isin, quantity, amount, notes, matchedHoldingId, kind, symbol, tickerSymbol, null);
    }

    /** The shape every row had before kind, symbol and ticker existed. */
    public ExtractedTransaction(String date, TransactionType type, String instrumentName, String isin,
                                BigDecimal quantity, BigDecimal amount, String notes, String matchedHoldingId) {
        this(date, type, instrumentName, isin, quantity, amount, notes, matchedHoldingId, null, null, null, null);
    }

    public ExtractedTransaction withMatchedHolding(String holdingId) {
        return new ExtractedTransaction(date, type, instrumentName, isin, quantity, amount, notes, holdingId, kind, symbol, tickerSymbol, account);
    }

    public ExtractedTransaction withTicker(String ticker) {
        return new ExtractedTransaction(date, type, instrumentName, isin, quantity, amount, notes, matchedHoldingId, kind, symbol, ticker, account);
    }
}
