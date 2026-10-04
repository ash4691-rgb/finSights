package com.finsights.portfolio.domain;

/** Derived (not self-selected) during persona onboarding, from a four-archetype framework built
 *  around age and portfolio size — see PersonaService.derivePersona. Distinct from
 *  {@link RiskProfile}, which is computed from the separate risk-scenario answers.
 *  Full framework (age range · portfolio size · core goal), shown to the user as onboarding
 *  option hints — see PERSONA_OPTIONS in the frontend's persona-onboarding.tsx:
 *    WEALTH_BUILDER            22-35  · $1K-$50K      · dollar-cost averaging, learning the basics
 *    ACTIVE_ACCUMULATOR        35-50  · $50K-$500K    · maximising 401k/IRA, outperforming the market
 *    HIGH_NET_WORTH_TACTICIAN  35-65  · $500K-$5M+    · capital preservation, estate planning, alpha
 *    DEFENSIVE_CONSUMER        55+    · $250K+        · income yield, principal protection, RMDs */
public enum InvestorPersona { WEALTH_BUILDER, ACTIVE_ACCUMULATOR, HIGH_NET_WORTH_TACTICIAN, DEFENSIVE_CONSUMER }
