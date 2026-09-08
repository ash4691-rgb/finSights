package com.finsights.portfolio.dto;

import java.util.List;

public record ImportResultResponse(int created, int updated, int skipped, List<RowError> errors) {
    public record RowError(int row, String message) { }
}
