package com.finsights.portfolio.domain;

/** Computed from the five scenario-question answers in persona onboarding — not directly
 *  user-chosen; a missing/skipped answer normalizes to "moderate", so MODERATE is the effective
 *  default whenever the questionnaire is incomplete (see PersonaService.scoreRisk/skip). Shown
 *  to the user under friendlier names and with an equity/debt-cash allocation guideline — see
 *  RISK_LABELS and RISK_ALLOCATION in the frontend's persona-onboarding.tsx:
 *    CONSERVATIVE  "Long-term investor"   ·  0-20% equity / 80-100% debt & cash  · Capital preservation
 *    MODERATE      "Swing trader"         · 40-50% equity /  50-60% debt & cash  · Balanced growth
 *    AGGRESSIVE    "High growth trader"   · 70-90% equity /  10-30% debt & cash  · Long-term wealth
 *  The constants here stay stable identifiers so a label/framework wording change never touches
 *  already-persisted data. */
public enum RiskProfile { CONSERVATIVE, MODERATE, AGGRESSIVE }
