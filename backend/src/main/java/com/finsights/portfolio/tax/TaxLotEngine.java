package com.finsights.portfolio.tax;

import com.finsights.portfolio.domain.TransactionType;
import java.math.BigDecimal;
import java.math.MathContext;
import java.time.LocalDate;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * FIFO lot matching, the same way HoldingService books realised P/L, but keeping each lot's
 * purchase date so a sale can be split into short- and long-term parts. Pure.
 *
 * <p>A holding whose ledger never records units (a manually valued asset) is tracked by value:
 * each BUY becomes a lot of {@code amount} units at cost 1, and a SELL without units closes every
 * open lot. Unit-less BUYs in a ledger that does record units only adjust cost elsewhere and are
 * left out here.
 */
public final class TaxLotEngine {
    private static final MathContext MC = MathContext.DECIMAL64;

    public record Entry(LocalDate date, TransactionType type, BigDecimal amount, BigDecimal units) { }

    public record RealisedLot(LocalDate bought, LocalDate sold, BigDecimal units, BigDecimal cost, BigDecimal proceeds) {
        public BigDecimal gain() { return proceeds.subtract(cost); }
    }

    public record OpenLot(LocalDate bought, BigDecimal units, BigDecimal cost) { }

    public record Result(List<RealisedLot> realised, List<OpenLot> open) { }

    private static final class Lot {
        final LocalDate bought;
        BigDecimal units;
        BigDecimal costPerUnit;

        Lot(LocalDate bought, BigDecimal units, BigDecimal costPerUnit) {
            this.bought = bought;
            this.units = units;
            this.costPerUnit = costPerUnit;
        }
    }

    private TaxLotEngine() { }

    /** {@code ledger} must be in date order, oldest first. */
    public static Result match(List<Entry> ledger) {
        boolean byValue = ledger.stream().noneMatch(e -> positive(e.units()));
        Deque<Lot> lots = new ArrayDeque<>();
        List<RealisedLot> realised = new ArrayList<>();
        for (Entry entry : ledger) {
            if (entry.type() == null) continue;
            switch (entry.type()) {
                case BUY, ADJUSTMENT -> addLot(lots, entry, byValue);
                case SELL -> realised.addAll(sell(lots, entry));
                case SPLIT -> split(lots, entry.units());
                case INTEREST, REPAY -> { /* no lots move */ }
            }
        }
        List<OpenLot> open = lots.stream()
                .map(lot -> new OpenLot(lot.bought, lot.units, lot.units.multiply(lot.costPerUnit, MC)))
                .toList();
        return new Result(realised, open);
    }

    private static void addLot(Deque<Lot> lots, Entry entry, boolean byValue) {
        BigDecimal amount = zeroIfNull(entry.amount());
        if (positive(entry.units())) {
            lots.addLast(new Lot(entry.date(), entry.units(), amount.divide(entry.units(), MC)));
            return;
        }
        if (byValue && amount.signum() > 0) lots.addLast(new Lot(entry.date(), amount, BigDecimal.ONE));
    }

    private static List<RealisedLot> sell(Deque<Lot> lots, Entry entry) {
        BigDecimal held = lots.stream().map(lot -> lot.units).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (held.signum() <= 0) return List.of();
        BigDecimal toSell = positive(entry.units()) ? entry.units().min(held) : held;
        BigDecimal proceeds = zeroIfNull(entry.amount());
        BigDecimal proceedsPerUnit = proceeds.divide(toSell, MC);
        List<RealisedLot> out = new ArrayList<>();
        BigDecimal remaining = toSell;
        BigDecimal proceedsLeft = proceeds;
        while (remaining.signum() > 0 && !lots.isEmpty()) {
            Lot lot = lots.peekFirst();
            BigDecimal take = remaining.min(lot.units);
            remaining = remaining.subtract(take);
            // The last slice takes whatever's left, so slices always add up to the exact proceeds.
            BigDecimal slice = remaining.signum() == 0 ? proceedsLeft : take.multiply(proceedsPerUnit, MC);
            proceedsLeft = proceedsLeft.subtract(slice);
            out.add(new RealisedLot(lot.bought, entry.date(), take, take.multiply(lot.costPerUnit, MC), slice));
            lot.units = lot.units.subtract(take);
            if (lot.units.signum() <= 0) lots.pollFirst();
        }
        return out;
    }

    private static void split(Deque<Lot> lots, BigDecimal ratio) {
        if (!positive(ratio)) return;
        for (Lot lot : lots) {
            lot.units = lot.units.multiply(ratio, MC);
            lot.costPerUnit = lot.costPerUnit.divide(ratio, MC);
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
