package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.Test;

class GoalMathTest {
    @Test
    void futureValueCompoundsMonthlyWithMonthEndSavings() {
        // 1,00,000 at 12% (1%/month) for 12 months: 100000 × 1.01^12 = 112,682.50
        assertThat(GoalMath.futureValue(100_000, 0, 12, 12)).isCloseTo(112_682.50, within(0.01));
        // 1,000/month for 12 months at 1%/month: 1000 × (1.01^12 − 1) / 0.01 = 12,682.50
        assertThat(GoalMath.futureValue(0, 1_000, 12, 12)).isCloseTo(12_682.50, within(0.01));
        assertThat(GoalMath.futureValue(500, 100, 0, 10)).isEqualTo(1_500);
        assertThat(GoalMath.futureValue(500, 100, 12, 0)).isEqualTo(500);
    }

    @Test
    void requiredMonthlyIsTheInverseOfFutureValue() {
        double monthly = GoalMath.requiredMonthly(50_000, 10_00_000, 10, 120);
        assertThat(GoalMath.futureValue(50_000, monthly, 10, 120)).isCloseTo(10_00_000, within(0.5));
        assertThat(GoalMath.requiredMonthly(0, 1_200, 0, 12)).isEqualTo(100);
    }

    @Test
    void nothingNeededWhenAlreadyOnTrackOrOutOfTime() {
        assertThat(GoalMath.requiredMonthly(2_000, 1_000, 8, 12)).isZero();
        assertThat(GoalMath.requiredMonthly(2_000, 1_000, 0, 12)).isZero();
        assertThat(GoalMath.requiredMonthly(0, 1_000, 8, 0)).isZero();
    }
}
