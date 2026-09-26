package com.finsights.portfolio.dto;

/** Whether the signed-in user should see the Goku nav entry, and whether they can manage its allowlist. */
public record GokuConfigResponse(boolean available, boolean admin) {}
