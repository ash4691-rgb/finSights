package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.HoldingKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CategoryRequest(
        @NotBlank @Size(min = 1, max = 128, message = "Name must be 1–128 characters") String name,
        @NotNull HoldingKind kind,
        @Size(max = 1024, message = "Description must be 1024 characters or fewer") String description
) { }
