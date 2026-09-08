package com.finsights.portfolio.dto;

/** One row of the ticker type-ahead: an exchange symbol and what it refers to. */
public record SymbolSuggestion(String symbol, String name, String exchange, String type) { }
