package com.finsights.portfolio.domain;

/** Self-reported current net worth, as a relative tier rather than a literal amount — one of the
 *  signals (alongside age) that PersonaService.derivePersona uses to pick an InvestorPersona
 *  archetype, which only ever compares these by rank, never by a fixed INR value. The frontend
 *  picks a currency-appropriate label for each tier based on the user's country of residence (see
 *  persona-onboarding.tsx's portfolioSizeOptionsFor) — "UNDER_1L" is a stable identifier left
 *  over from the first (India-only) version of this enum, not a claim that every user sees
 *  lakhs. */
public enum PortfolioSize { UNDER_1L, L1_TO_10L, L10_TO_50L, L50_TO_2CR, ABOVE_2CR, PREFER_NOT_TO_SAY }
