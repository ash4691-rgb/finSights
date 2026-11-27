package com.finsights.portfolio.domain;

/** How Indian income tax treats gains on a holding. See TaxRules for the rates and periods. */
public enum TaxClass {
    /** Listed shares, equity mutual funds and equity ETFs: long-term after 12 months. */
    LISTED_EQUITY,
    /** Gold, foreign or unlisted shares, real estate: long-term after 24 months. */
    OTHER_CAPITAL,
    /** Debt funds bought after March 2023, FDs, bonds: all gains and interest at the slab rate. */
    DEBT_SLAB,
    /** Crypto (virtual digital assets): flat 30%, losses can't be set off. */
    VIRTUAL_DIGITAL_ASSET,
    /** EPF, PPF and similar: gains and interest not taxed. */
    EXEMPT
}
