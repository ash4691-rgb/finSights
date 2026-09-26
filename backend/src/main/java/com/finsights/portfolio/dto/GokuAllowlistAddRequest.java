package com.finsights.portfolio.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record GokuAllowlistAddRequest(@NotBlank @Email String email) {}
