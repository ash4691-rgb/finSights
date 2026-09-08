package com.finsights.portfolio.domain;

public enum CompoundingFrequency {
    DAILY(365), WEEKLY(52), MONTHLY(12), QUARTERLY(4), HALF_YEARLY(2), ANNUALLY(1);

    private final int periodsPerYear;

    CompoundingFrequency(int periodsPerYear) {
        this.periodsPerYear = periodsPerYear;
    }

    public int periodsPerYear() {
        return periodsPerYear;
    }
}

