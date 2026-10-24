package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ExtractedHolding;
import com.finsights.portfolio.dto.ExtractedTransaction;
import com.finsights.portfolio.dto.InstrumentKind;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Pure row-shaping helpers for statement import, kept out of the service so they stay small and
 *  unit-testable without a network call or repository. */
final class StatementRows {
    private static final int COST_SCALE = 4;

    private StatementRows() { }

    /** Identity of a holding row across a statement: its ISIN when it has one, otherwise its
     *  normalised name — a mutual fund folio export commonly has no ISIN at all. */
    static String holdingKey(ExtractedHolding row) {
        if (row.isin() != null) return row.isin();
        return row.instrumentName().trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /** Same identity rule as {@link #holdingKey} for a transaction line: ISIN, else normalised name. */
    static String transactionKey(ExtractedTransaction row) {
        if (row.isin() != null) return row.isin();
        return row.instrumentName().trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    /**
     * Merges rows that are the same instrument into one: units, invested value and current value
     * are summed and the average cost is recomputed from the totals. A statement lists one row per
     * folio, and a holding here is one per (name, broker), so two folios of one scheme must become
     * one holding. Rows keep their first-seen order; a stock/ETF repeated by ISIN keeps its first
     * sighting, as it always has (the same line can appear in two overlapping chunks).
     */
    static List<ExtractedHolding> consolidate(List<ExtractedHolding> rows) {
        Map<String, ExtractedHolding> byKey = new LinkedHashMap<>();
        for (ExtractedHolding row : rows) {
            String key = holdingKey(row) + "|" + accountKey(row.account());
            ExtractedHolding seen = byKey.get(key);
            if (seen == null) { byKey.put(key, row); continue; }
            if (row.kind() != null && row.kind().identifiedByName()) byKey.put(key, combine(seen, row));
        }
        return new ArrayList<>(byKey.values());
    }

    private static ExtractedHolding combine(ExtractedHolding a, ExtractedHolding b) {
        BigDecimal quantity = a.quantity().add(b.quantity());
        BigDecimal invested = sumNullable(a.investedValue(), b.investedValue());
        BigDecimal current = sumNullable(a.currentValue(), b.currentValue());
        return new ExtractedHolding(a.instrumentName(), a.isin(), quantity, averageCost(invested, quantity),
                a.depository(), a.tickerSymbol(), a.matchedHoldingId(), a.kind(), invested, current, a.symbol(), a.account(), a.currency());
    }

    /** A total is only known if every contributing row stated one; a gap makes it unknown, not smaller. */
    private static BigDecimal sumNullable(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) return null;
        return a.add(b);
    }

    /** The same instrument held through two institutions is two positions, so the account is part of a row's identity. */
    private static String accountKey(String account) {
        return account == null ? "" : account.trim().toLowerCase(Locale.ROOT);
    }

    /** Rows with the account dropped, then consolidated: once the user maps accounts to brokers, two
     *  accounts at one broker are one position per instrument. */
    static List<ExtractedHolding> consolidateAcrossAccounts(List<ExtractedHolding> rows) {
        return consolidate(rows.stream().map(row -> row.withAccount(null)).toList());
    }

    static BigDecimal averageCost(BigDecimal investedValue, BigDecimal quantity) {
        if (investedValue == null || quantity == null || quantity.signum() <= 0) return null;
        return investedValue.divide(quantity, COST_SCALE, RoundingMode.HALF_UP);
    }

    /** A holding a holdings statement could reasonably be expected to still list: a market-linked
     *  asset with units. Liabilities, real estate (area-based) and fixed-rate deposits never appear
     *  on a broker statement, so their absence says nothing. */
    static boolean listedOnBrokerStatements(Holding holding) {
        if (holding.getQuantity() == null || holding.getQuantity().signum() <= 0) return false;
        if (holding.getValuationMethod() == ValuationMethod.FIXED_RATE) return false;
        if (holding.getCategory() == null) return true;
        return holding.getCategory().getKind() != HoldingKind.LIABILITY && !holding.getCategory().isRealEstate();
    }

    /**
     * Whether a holding's absence from a statement means anything: a statement of only mutual funds
     * says nothing about the stocks held at the same broker, and the other way round. A holding with
     * no ticker can't be told apart, so it stays eligible, and a statement with no holding rows at
     * all constrains nothing.
     */
    static boolean couldBeListed(Holding holding, List<ExtractedHolding> statementRows) {
        if (statementRows.isEmpty() || holding.getTickerSymbol() == null) return true;
        boolean fund = holding.getTickerSymbol().startsWith("MF:");
        return statementRows.stream().anyMatch(row -> (row.kind() == InstrumentKind.MUTUAL_FUND) == fund);
    }

    static boolean sameBroker(String a, String b) {
        if (a == null || b == null) return false;
        return a.trim().equalsIgnoreCase(b.trim());
    }
}
