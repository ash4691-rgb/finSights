package com.finsights.portfolio.service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.OptionalDouble;

/**
 * Annualised internal rate of return for irregular cash flows (Excel's XIRR, Actual/365).
 * Newton–Raphson from a 10% guess, falling back to bisection when Newton diverges or leaves
 * the valid range. Pure and stateless.
 */
public final class XirrCalculator {
    static final double MIN_RATE = -0.9999;
    static final double MAX_RATE = 100.0;          // 10,000% a year: anything above is noise
    private static final double TOLERANCE = 1e-7;
    private static final int MAX_NEWTON_ITERATIONS = 50;
    private static final double DAYS_PER_YEAR = 365.0;

    private XirrCalculator() { }

    /** Empty when the flows can't have an IRR: fewer than two, or not both an outflow and an inflow. */
    public static OptionalDouble xirr(List<CashFlow> flows) {
        if (!hasBothSigns(flows)) return OptionalDouble.empty();
        LocalDate start = flows.stream().map(CashFlow::date).min(Comparator.naturalOrder()).orElseThrow();
        double[] years = flows.stream().mapToDouble(f -> ChronoUnit.DAYS.between(start, f.date()) / DAYS_PER_YEAR).toArray();
        double[] amounts = flows.stream().mapToDouble(CashFlow::amount).toArray();

        OptionalDouble newton = newton(years, amounts);
        if (newton.isPresent()) return newton;
        return bisection(years, amounts);
    }

    static boolean hasBothSigns(List<CashFlow> flows) {
        if (flows == null || flows.size() < 2) return false;
        boolean anyNegative = flows.stream().anyMatch(f -> f.amount() < 0);
        boolean anyPositive = flows.stream().anyMatch(f -> f.amount() > 0);
        return anyNegative && anyPositive;
    }

    static double npv(double rate, double[] years, double[] amounts) {
        double total = 0;
        for (int i = 0; i < amounts.length; i++) total += amounts[i] / Math.pow(1 + rate, years[i]);
        return total;
    }

    private static double derivative(double rate, double[] years, double[] amounts) {
        double total = 0;
        for (int i = 0; i < amounts.length; i++) total -= years[i] * amounts[i] / Math.pow(1 + rate, years[i] + 1);
        return total;
    }

    private static OptionalDouble newton(double[] years, double[] amounts) {
        double rate = 0.1;
        for (int i = 0; i < MAX_NEWTON_ITERATIONS; i++) {
            double value = npv(rate, years, amounts);
            if (Math.abs(value) < TOLERANCE) return OptionalDouble.of(rate);
            double slope = derivative(rate, years, amounts);
            if (slope == 0 || !Double.isFinite(slope)) return OptionalDouble.empty();
            double next = rate - value / slope;
            if (!Double.isFinite(next) || next <= MIN_RATE || next > MAX_RATE) return OptionalDouble.empty();
            if (Math.abs(next - rate) < TOLERANCE) return OptionalDouble.of(next);
            rate = next;
        }
        return OptionalDouble.empty();
    }

    /** Halves [MIN_RATE, MAX_RATE] until it is narrower than the tolerance; always terminates (~30 steps). */
    static OptionalDouble bisection(double[] years, double[] amounts) {
        double low = MIN_RATE;
        double high = MAX_RATE;
        double lowSign = Math.signum(npv(low, years, amounts));
        if (lowSign == Math.signum(npv(high, years, amounts))) return OptionalDouble.empty();
        while (high - low > TOLERANCE) {
            double mid = (low + high) / 2;
            if (Math.signum(npv(mid, years, amounts)) == lowSign) {
                low = mid;
            } else {
                high = mid;
            }
        }
        return OptionalDouble.of((low + high) / 2);
    }
}
