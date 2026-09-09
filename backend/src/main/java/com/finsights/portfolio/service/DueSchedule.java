package com.finsights.portfolio.service;

import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Shared scheduling for anything that falls due on a fixed cadence — fixed-rate
 * interest payouts and recurring loan instalments.
 *
 * <p>The schedule is the grid {@code firstDue, firstDue + step, firstDue + 2·step, …}.
 * The next date that needs attention is always the earliest grid date after
 * {@code max(firstDue − step, last settled date)} — i.e. we walk the grid and skip
 * every date that has already been settled, with no "lookback window" so a long-
 * overdue item never quietly disappears.
 */
final class DueSchedule {

    private DueSchedule() { }

    /**
     * Unsettled grid dates, earliest first: {@code firstDue + k·step} for k ≥ 0, minus any
     * date in {@code settled}, stopping once past {@code horizon} (and {@code end}, when non-null),
     * capped at {@code max} entries.
     */
    static List<LocalDate> unsettled(LocalDate firstDue, Period step, Set<LocalDate> settled,
                                     LocalDate horizon, LocalDate end, int max) {
        List<LocalDate> out = new ArrayList<>();
        if (firstDue == null || step == null || step.isZero()) return out;
        LocalDate d = firstDue;
        int guard = 0;
        while (!d.isAfter(horizon) && (end == null || !d.isAfter(end)) && out.size() < max && guard++ < 100_000) {
            if (!settled.contains(d)) out.add(d);
            d = d.plus(step);
        }
        return out;
    }

    /** Largest grid date {@code firstDue + k·step} that is ≤ {@code date}; null when {@code date} precedes {@code firstDue}. */
    static LocalDate snapToPeriod(LocalDate firstDue, Period step, LocalDate date) {
        if (firstDue == null || step == null || step.isZero() || date == null || date.isBefore(firstDue)) return null;
        LocalDate d = firstDue;
        int guard = 0;
        while (!d.plus(step).isAfter(date) && guard++ < 100_000) d = d.plus(step);
        return d;
    }
}
