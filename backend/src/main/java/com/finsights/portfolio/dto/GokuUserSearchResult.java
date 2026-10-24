package com.finsights.portfolio.dto;

/** One typeahead match from {@code GET /api/goku/admin/users?q=} — their current Goku access, so the
 *  admin panel's dropdown opens already showing the right value instead of defaulting to NO_ACCESS. */
public record GokuUserSearchResult(String email, String displayName, GokuModelTier tier) {}
