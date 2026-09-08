package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotEmpty;
import java.math.BigDecimal;
import java.util.Set;

/** Bulk-edits holdings (broker/owner/value) — Liquid, NPA, and tags now live on the instrument, edited one at a time. */
public record BulkUpdateRequest(
        @NotEmpty Set<String> ids,
        String broker,
        String ownerName,
        BigDecimal currentValue
) { }
