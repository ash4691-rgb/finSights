package com.finsights.portfolio.dto;

import java.util.List;

/**
 * Result of one CAS PDF upload. {@code warnings} carries anything the model noticed but didn't
 * turn into a holding — a row it wasn't confident about, an instrument type out of scope for this
 * pilot (mutual funds, bonds), a page it couldn't read — so a short statement isn't silently
 * under-reported with no explanation.
 */
public record CasExtractionResponse(List<ExtractedHolding> holdings, String statementDate, List<String> warnings) { }
