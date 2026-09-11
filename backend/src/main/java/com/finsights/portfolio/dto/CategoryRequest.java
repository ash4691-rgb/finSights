package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.Set;

public record CategoryRequest(
        @NotBlank @Size(min = 1, max = 128, message = "Name must be 1–128 characters") String name,
        @NotNull HoldingKind kind,
        @Size(max = 1024, message = "Description must be 1024 characters or fewer") String description,
        /** Empty or null means no restriction — every valuation method is allowed. */
        Set<ValuationMethod> allowedValuationMethods
) { }
