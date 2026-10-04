package com.finsights.portfolio.domain;

/** Self-reported current portfolio size, in INR — one of the signals (alongside age) that
 *  PersonaService.derivePersona uses to pick an InvestorPersona archetype, rather than asking
 *  the user to self-select one directly. */
public enum PortfolioSize { UNDER_1L, L1_TO_10L, L10_TO_50L, L50_TO_2CR, ABOVE_2CR, PREFER_NOT_TO_SAY }
