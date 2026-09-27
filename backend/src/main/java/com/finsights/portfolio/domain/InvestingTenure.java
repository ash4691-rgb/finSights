package com.finsights.portfolio.domain;

/** How long the user has been actively investing — collected alongside {@link InvestorExperience}
 *  during persona onboarding, since a self-identified label ("Newbie", "Professional trader")
 *  means little without a sense of actual track record behind it. */
public enum InvestingTenure { UNDER_1_YEAR, ONE_TO_3_YEARS, THREE_TO_10_YEARS, OVER_10_YEARS }
