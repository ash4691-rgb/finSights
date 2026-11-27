package com.finsights.portfolio.service;

/**
 * Monthly-compounded future value of today's amount plus a fixed monthly saving, and its inverse.
 * Pure. Rates are annual percentages, compounded monthly; contributions land at each month end.
 */
public final class GoalMath {
    private GoalMath() { }

    public static double futureValue(double present, double monthly, double annualPercent, int months) {
        if (months <= 0) return present;
        double r = monthlyRate(annualPercent);
        if (r == 0) return present + monthly * months;
        double growth = Math.pow(1 + r, months);
        return present * growth + monthly * (growth - 1) / r;
    }

    /** Monthly saving needed to reach {@code target}; 0 when today's amount already gets there. */
    public static double requiredMonthly(double present, double target, double annualPercent, int months) {
        if (months <= 0) return 0;
        double r = monthlyRate(annualPercent);
        double grownPresent = r == 0 ? present : present * Math.pow(1 + r, months);
        double gap = target - grownPresent;
        if (gap <= 0) return 0;
        if (r == 0) return gap / months;
        return gap * r / (Math.pow(1 + r, months) - 1);
    }

    private static double monthlyRate(double annualPercent) {
        return annualPercent / 100 / 12;
    }
}
