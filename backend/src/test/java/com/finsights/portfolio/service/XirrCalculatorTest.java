package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class XirrCalculatorTest {
    private static final LocalDate D0 = LocalDate.of(2024, 1, 1);

    @Test
    void doublingInExactlyOneYearIsOneHundredPercent() {
        var flows = List.of(new CashFlow(D0, -1000), new CashFlow(D0.plusDays(365), 2000));
        assertThat(XirrCalculator.xirr(flows).getAsDouble()).isCloseTo(1.0, within(1e-6));
    }

    @Test
    void matchesExcelReferenceExample() {
        // Microsoft's XIRR documentation example; Excel returns 0.373362535.
        var flows = List.of(
                new CashFlow(LocalDate.of(2008, 1, 1), -10000),
                new CashFlow(LocalDate.of(2008, 3, 1), 2750),
                new CashFlow(LocalDate.of(2008, 10, 30), 4250),
                new CashFlow(LocalDate.of(2009, 2, 15), 3250),
                new CashFlow(LocalDate.of(2009, 4, 1), 2750));
        assertThat(XirrCalculator.xirr(flows).getAsDouble()).isCloseTo(0.373362535, within(1e-6));
    }

    @Test
    void lossIsNegative() {
        var flows = List.of(new CashFlow(D0, -1000), new CashFlow(D0.plusDays(365), 800));
        assertThat(XirrCalculator.xirr(flows).getAsDouble()).isCloseTo(-0.2, within(1e-6));
    }

    @Test
    void multipleContributionsSolve() {
        var flows = List.of(
                new CashFlow(D0, -1000), new CashFlow(D0.plusMonths(6), -1000),
                new CashFlow(D0.plusYears(2), 2500));
        double rate = XirrCalculator.xirr(flows).getAsDouble();
        assertThat(rate).isPositive();
        assertThat(npv(rate, flows)).isCloseTo(0, within(1e-4));
    }

    @Test
    void steepLossStillSolves() {
        var flows = List.of(new CashFlow(D0, -1000), new CashFlow(D0.plusDays(365), 50));
        assertThat(XirrCalculator.xirr(flows).getAsDouble()).isCloseTo(-0.95, within(1e-5));
    }

    @Test
    void bisectionFindsTheSameRootNewtonDoes() {
        double[] years = {0, 1};
        double[] amounts = {-1000, 1100};
        assertThat(XirrCalculator.bisection(years, amounts).getAsDouble()).isCloseTo(0.1, within(1e-5));
    }

    @Test
    void bisectionWithoutASignChangeHasNoAnswer() {
        double[] years = {0, 1};
        double[] amounts = {-1000, -1};
        assertThat(XirrCalculator.bisection(years, amounts)).isEmpty();
    }

    @Test
    void absurdGainBeyondTheCapHasNoAnswer() {
        var flows = List.of(new CashFlow(D0, -1), new CashFlow(D0.plusDays(1), 1_000_000));
        assertThat(XirrCalculator.xirr(flows)).isEmpty();
    }

    @Test
    void needsBothAnOutflowAndAnInflow() {
        assertThat(XirrCalculator.xirr(List.of(new CashFlow(D0, -1000), new CashFlow(D0.plusDays(10), -5)))).isEmpty();
        assertThat(XirrCalculator.xirr(List.of(new CashFlow(D0, 1000), new CashFlow(D0.plusDays(10), 5)))).isEmpty();
        assertThat(XirrCalculator.xirr(List.of(new CashFlow(D0, -1000)))).isEmpty();
        assertThat(XirrCalculator.xirr(null)).isEmpty();
    }

    @Test
    void zeroFlowsDoNotCountAsEitherSign() {
        assertThat(XirrCalculator.hasBothSigns(List.of(new CashFlow(D0, 0), new CashFlow(D0, 100)))).isFalse();
    }

    @Test
    void flowsOnTheSameDayHaveNoAnswer() {
        // All flows at t=0: NPV is constant, Newton's slope is zero and there's no sign change.
        var flows = List.of(new CashFlow(D0, -1000), new CashFlow(D0, 500));
        assertThat(XirrCalculator.xirr(flows)).isEmpty();
    }

    private static double npv(double rate, List<CashFlow> flows) {
        double[] years = flows.stream().mapToDouble(f -> java.time.temporal.ChronoUnit.DAYS.between(D0, f.date()) / 365.0).toArray();
        double[] amounts = flows.stream().mapToDouble(CashFlow::amount).toArray();
        return XirrCalculator.npv(rate, years, amounts);
    }
}
