package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.HoldingKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CategoryRequest(@NotBlank String name, @NotNull HoldingKind kind, String description) { }
