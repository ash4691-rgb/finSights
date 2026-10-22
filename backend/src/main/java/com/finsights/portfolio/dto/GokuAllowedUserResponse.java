package com.finsights.portfolio.dto;

import java.time.Instant;

/** {@code model} is always the effective model for this user — their own override, or the app-wide default. */
public record GokuAllowedUserResponse(String email, Instant addedAt, String addedBy, String model) {}
