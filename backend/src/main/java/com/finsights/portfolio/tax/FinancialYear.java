package com.finsights.portfolio.tax;

import java.time.LocalDate;
import java.time.Month;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** An Indian financial year, 1 April to 31 March, labelled like "2025-26". */
public record FinancialYear(int startYear) {
    private static final Pattern LABEL = Pattern.compile("(\\d{4})-(\\d{2})");

    public static FinancialYear of(LocalDate date) {
        int year = date.getMonthValue() >= Month.APRIL.getValue() ? date.getYear() : date.getYear() - 1;
        return new FinancialYear(year);
    }

    /** Parses "2025-26"; null or blank gives {@code fallback}. Rejects anything else. */
    public static FinancialYear parse(String label, FinancialYear fallback) {
        if (label == null || label.isBlank()) return fallback;
        Matcher matcher = LABEL.matcher(label.trim());
        if (!matcher.matches()) throw new IllegalArgumentException("Financial year must look like 2025-26");
        int start = Integer.parseInt(matcher.group(1));
        if (Integer.parseInt(matcher.group(2)) != (start + 1) % 100) {
            throw new IllegalArgumentException("Financial year must look like 2025-26");
        }
        return new FinancialYear(start);
    }

    public LocalDate start() { return LocalDate.of(startYear, Month.APRIL, 1); }

    public LocalDate end() { return LocalDate.of(startYear + 1, Month.MARCH, 31); }

    public boolean contains(LocalDate date) {
        return date != null && !date.isBefore(start()) && !date.isAfter(end());
    }

    public String label() { return startYear + "-" + String.format("%02d", (startYear + 1) % 100); }
}
