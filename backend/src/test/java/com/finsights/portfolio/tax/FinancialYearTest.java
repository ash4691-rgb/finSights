package com.finsights.portfolio.tax;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class FinancialYearTest {
    @Test
    void aprilStartsTheYearAndMarchEndsIt() {
        assertThat(FinancialYear.of(LocalDate.of(2026, 3, 31)).label()).isEqualTo("2025-26");
        assertThat(FinancialYear.of(LocalDate.of(2026, 4, 1)).label()).isEqualTo("2026-27");
        assertThat(FinancialYear.of(LocalDate.of(1999, 6, 1)).label()).isEqualTo("1999-00");
        FinancialYear year = new FinancialYear(2025);
        assertThat(year.start()).isEqualTo(LocalDate.of(2025, 4, 1));
        assertThat(year.end()).isEqualTo(LocalDate.of(2026, 3, 31));
        assertThat(year.contains(LocalDate.of(2025, 4, 1))).isTrue();
        assertThat(year.contains(LocalDate.of(2026, 3, 31))).isTrue();
        assertThat(year.contains(LocalDate.of(2025, 3, 31))).isFalse();
        assertThat(year.contains(LocalDate.of(2026, 4, 1))).isFalse();
        assertThat(year.contains(null)).isFalse();
    }

    @Test
    void parsesLabelsAndFallsBackWhenBlank() {
        FinancialYear fallback = new FinancialYear(2020);
        assertThat(FinancialYear.parse("2025-26", fallback).startYear()).isEqualTo(2025);
        assertThat(FinancialYear.parse(" 1999-00 ", fallback).startYear()).isEqualTo(1999);
        assertThat(FinancialYear.parse(null, fallback)).isSameAs(fallback);
        assertThat(FinancialYear.parse(" ", fallback)).isSameAs(fallback);
        assertThatThrownBy(() -> FinancialYear.parse("2025", fallback)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FinancialYear.parse("2025-27", fallback)).hasMessageContaining("2025-26");
    }
}
