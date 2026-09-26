package com.finsights.portfolio.dto;

import java.time.Instant;

public record GokuAllowedUserResponse(String email, Instant addedAt, String addedBy) {}
