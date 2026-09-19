package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.ImportResultResponse;
import com.finsights.portfolio.dto.ImportResultResponse.RowError;
import com.finsights.portfolio.dto.TransactionRequest;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.IntUnaryOperator;
import java.util.stream.Collectors;

/**
 * Runs the create-or-update loop shared by CSV and XML transaction import: each row is a
 * lower-cased field map (see {@link TransactionRowParser#FIELDS}); a row with a non-blank
 * {@code id} updates that transaction, otherwise a new one is logged against {@code holdingId}.
 * One bad row is recorded as an error and does not stop the rest of the import.
 *
 * <p>{@link TransactionRowParser} builds a {@code TransactionRequest} in plain Java, which skips
 * {@code @Valid} (that annotation only fires at the HTTP controller boundary) — so every row is
 * explicitly re-validated here against the same bean-validation rules the manual-entry form uses,
 * to prevent an import from writing dirtier data than the UI would ever allow.
 */
final class TransactionImportRunner {
    private TransactionImportRunner() { }

    static ImportResultResponse run(List<Map<String, String>> rows, IntUnaryOperator displayRowNumber,
                                     TransactionService transactions, Validator validator) {
        int created = 0, updated = 0, skipped = 0;
        List<RowError> errors = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, String> row = rows.get(i);
            if (row.values().stream().allMatch(v -> v == null || v.isBlank())) continue;
            Function<String, String> field = key -> row.getOrDefault(key, "");
            try {
                String id = TransactionRowParser.id(field);
                TransactionRequest request = TransactionRowParser.toRequest(field);
                validate(request, validator);
                if (id != null) {
                    transactions.update(id, request);
                    updated++;
                } else {
                    transactions.create(request);
                    created++;
                }
            } catch (Exception ex) {
                skipped++;
                errors.add(new RowError(displayRowNumber.applyAsInt(i),
                        ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
            }
        }
        return new ImportResultResponse(created, updated, skipped, errors);
    }

    private static void validate(TransactionRequest request, Validator validator) {
        Set<ConstraintViolation<TransactionRequest>> violations = validator.validate(request);
        if (!violations.isEmpty()) {
            throw new IllegalArgumentException(violations.stream()
                    .map(v -> v.getPropertyPath() + " " + v.getMessage())
                    .collect(Collectors.joining("; ")));
        }
    }
}
