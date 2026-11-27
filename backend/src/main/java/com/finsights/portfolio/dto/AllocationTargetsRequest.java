package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.Map;

/** Category id → target percent (0–100), or null to clear that category's target. Categories not listed keep theirs. */
public record AllocationTargetsRequest(@NotNull Map<String, BigDecimal> targets) { }
