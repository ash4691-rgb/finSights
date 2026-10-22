package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotNull;

public record GokuAccessRequest(@NotNull GokuModelTier tier) {}
