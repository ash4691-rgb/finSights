package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.ActionDismissalStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Marks one Action Centre row done / deferred / deleted. {@code deferDays} applies to DEFERRED only. */
public record ActionDismissalRequest(
        @NotBlank String key,
        @NotNull ActionDismissalStatus status,
        Integer deferDays
) { }
