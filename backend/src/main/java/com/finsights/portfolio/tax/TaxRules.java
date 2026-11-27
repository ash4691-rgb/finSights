package com.finsights.portfolio.tax;

import com.finsights.portfolio.domain.TaxClass;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Indian capital-gains rules FinSights estimates with. Finance (No. 2) Act 2024 changed rates for
 * transfers on or after 23 July 2024; earlier sales keep the old rates. Estimates exclude cess and
 * surcharge, and anything taxed at the user's slab is reported, not estimated.
 */
public final class TaxRules {
    public static final LocalDate RATE_CHANGE = LocalDate.of(2024, 7, 23);
    public static final BigDecimal VDA_RATE = new BigDecimal("0.30");
    private static final BigDecimal EQUITY_STCG_OLD = new BigDecimal("0.15");
    private static final BigDecimal EQUITY_STCG_NEW = new BigDecimal("0.20");
    private static final BigDecimal EQUITY_LTCG_OLD = new BigDecimal("0.10");
    private static final BigDecimal LTCG_NEW = new BigDecimal("0.125");
    private static final BigDecimal EXEMPTION_OLD = new BigDecimal("100000");
    private static final BigDecimal EXEMPTION_NEW = new BigDecimal("125000");
    private static final int EXEMPTION_RAISED_IN_FY = 2024;

    /** How a realised gain is treated. */
    public enum Term { SHORT, LONG, FLAT, SLAB, EXEMPT }

    private TaxRules() { }

    /** Months after which a holding is long-term; 0 where holding period doesn't matter. */
    public static int longTermAfterMonths(TaxClass taxClass) {
        return switch (taxClass) {
            case LISTED_EQUITY -> 12;
            case OTHER_CAPITAL -> 24;
            case DEBT_SLAB, VIRTUAL_DIGITAL_ASSET, EXEMPT -> 0;
        };
    }

    public static Term term(TaxClass taxClass, LocalDate bought, LocalDate sold) {
        return switch (taxClass) {
            case DEBT_SLAB -> Term.SLAB;
            case VIRTUAL_DIGITAL_ASSET -> Term.FLAT;
            case EXEMPT -> Term.EXEMPT;
            case LISTED_EQUITY, OTHER_CAPITAL -> sold.isBefore(longTermFrom(taxClass, bought)) ? Term.SHORT : Term.LONG;
        };
    }

    /** First day a lot bought on {@code bought} counts as long-term (held for more than the period). */
    public static LocalDate longTermFrom(TaxClass taxClass, LocalDate bought) {
        return bought.plusMonths(longTermAfterMonths(taxClass)).plusDays(1);
    }

    public static BigDecimal equityStcgRate(LocalDate sold) {
        return sold.isBefore(RATE_CHANGE) ? EQUITY_STCG_OLD : EQUITY_STCG_NEW;
    }

    public static BigDecimal equityLtcgRate(LocalDate sold) {
        return sold.isBefore(RATE_CHANGE) ? EQUITY_LTCG_OLD : LTCG_NEW;
    }

    /** Null before the rate change: those sales used 20% with indexation, which FinSights doesn't compute. */
    public static BigDecimal otherLtcgRate(LocalDate sold) {
        return sold.isBefore(RATE_CHANGE) ? null : LTCG_NEW;
    }

    /** Yearly exemption on long-term gains from listed equity. */
    public static BigDecimal equityLtcgExemption(FinancialYear year) {
        return year.startYear() >= EXEMPTION_RAISED_IN_FY ? EXEMPTION_NEW : EXEMPTION_OLD;
    }
}
