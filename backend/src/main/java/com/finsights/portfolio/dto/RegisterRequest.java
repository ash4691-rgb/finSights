package com.finsights.portfolio.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterRequest(
        @Email @NotBlank String email,
        String displayName,
        @NotBlank @Size(min = 8, max = 100, message = "Password must be at least 8 characters") String password
) { }
