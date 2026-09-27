package com.finsights.portfolio.domain;

/** Computed from the five scenario-question answers in persona onboarding — not directly
 *  user-chosen. See PersonaService for the scoring. Shown to the user under friendlier names
 *  ("Long-term investor" / "Swing trader" / "High growth trader" — see RISK_LABELS in the
 *  frontend's persona-onboarding.tsx); the constants here stay stable identifiers so a label
 *  wording change never touches already-persisted data. */
public enum RiskProfile { CONSERVATIVE, MODERATE, AGGRESSIVE }
