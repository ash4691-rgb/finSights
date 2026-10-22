package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;

public record GokuModelAssignRequest(@NotBlank String model) {}
