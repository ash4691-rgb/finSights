package com.finsights.portfolio.domain;

/** Self-reported annual income, as a relative tier rather than a literal amount — the backend
 *  only ever compares these by rank (see PersonaService.derivePersona), never by a fixed INR
 *  value. The frontend picks a currency-appropriate label for each tier based on the user's
 *  country of residence (see persona-onboarding.tsx's salaryOptionsFor) — "UNDER_5L" is a stable
 *  identifier left over from the first (India-only) version of this enum, not a claim that every
 *  user sees lakhs. */
public enum SalaryRange { UNDER_5L, L5_TO_10L, L10_TO_25L, L25_TO_50L, ABOVE_50L, PREFER_NOT_TO_SAY }
