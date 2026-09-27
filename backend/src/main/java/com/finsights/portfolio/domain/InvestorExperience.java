package com.finsights.portfolio.domain;

/** Self-identified during persona onboarding — how experienced the user is as an investor.
 *  Distinct from {@link RiskProfile}, which is computed from the risk-scenario answers. */
public enum InvestorExperience { NEWBIE, MODERATE, PROFESSIONAL_TRADER }
