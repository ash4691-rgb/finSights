package com.finsights.portfolio.dto;

/** The outcome of a {@code PUT /api/goku/admin/allowlist/access} call — the user's tier after the change. */
public record GokuAccessResult(String email, GokuModelTier tier) {}
