package com.finsights.portfolio.dto;

/**
 * Whether the signed-in user should see the Goku nav entry, and whether they can manage its
 * allowlist. {@code reason} explains why {@code available} is false — populated only for an
 * admin, so a non-admin never learns anything about the feature's configuration.
 */
public record GokuConfigResponse(boolean available, boolean admin, String reason) {}
