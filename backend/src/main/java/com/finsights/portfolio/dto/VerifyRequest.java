package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;

public record VerifyRequest(@NotBlank String token) { }
