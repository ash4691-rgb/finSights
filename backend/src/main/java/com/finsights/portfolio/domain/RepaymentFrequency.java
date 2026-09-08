package com.finsights.portfolio.domain;

import java.time.Period;

/** How a liability is repaid. ONE_TIME is a single bullet payment; the rest are recurring instalments. */
public enum RepaymentFrequency {
    WEEKLY(Period.ofWeeks(1)),
    MONTHLY(Period.ofMonths(1)),
    QUARTERLY(Period.ofMonths(3)),
    YEARLY(Period.ofYears(1)),
    ONE_TIME(null);

    private final Period step;

    RepaymentFrequency(Period step) {
        this.step = step;
    }

    public Period step() {
        return step;
    }

    public boolean recurring() {
        return step != null;
    }
}
