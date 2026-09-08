package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.TransactionRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.function.Function;

/**
 * Turns one imported row — CSV columns or XML {@code <transaction>} child elements, both
 * exposed the same way as a lower-cased field lookup — into a {@link TransactionRequest}.
 * Shared by {@link CsvService} and {@link XmlService}.
 */
final class TransactionRowParser {
    /** Column / element names understood by both formats, in canonical order. */
    static final java.util.List<String> FIELDS = java.util.List.of(
            "id", "holdingId", "type", "date", "amount", "quantity", "notes");

    private TransactionRowParser() { }

    static String id(Function<String, String> field) {
        return blankToNull(field.apply("id"));
    }

    static TransactionRequest toRequest(Function<String, String> field) {
        return new TransactionRequest(
                require(field.apply("holdingid"), "holdingId"),
                parseEnum(TransactionType.class, field.apply("type"), "type"),
                requireDate(field.apply("date")),
                decimal(field.apply("amount"), "amount"),
                optionalDecimal(field.apply("quantity"), "quantity"),
                blankToNull(field.apply("notes")));
    }

    static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        try {
            return Enum.valueOf(type, value.trim().toUpperCase().replace(' ', '_'));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " '" + value + "' is not one of "
                    + Arrays.toString(type.getEnumConstants()));
        }
    }

    private static BigDecimal decimal(String value, String field) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value.replace(",", "").trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(field + " '" + value + "' is not a number");
        }
    }

    private static BigDecimal optionalDecimal(String value, String field) {
        return value == null || value.isBlank() ? null : decimal(value, field);
    }

    private static LocalDate requireDate(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("date is required");
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ex) {
            throw new IllegalArgumentException("date '" + value + "' must be YYYY-MM-DD");
        }
    }
}
