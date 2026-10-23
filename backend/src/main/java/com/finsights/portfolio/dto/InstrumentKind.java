package com.finsights.portfolio.dto;

/**
 * What kind of market-linked instrument an extracted statement row is. Drives how the row is
 * identified (a mutual fund folio has no ISIN in most broker exports, only a scheme name) and how
 * its ticker is guessed (AMFI scheme code vs. an NSE/BSE listing). Crypto and commodities are
 * deliberately not here yet — rows of any other kind are reported in {@code warnings} instead.
 */
public enum InstrumentKind { STOCK, ETF, MUTUAL_FUND }
