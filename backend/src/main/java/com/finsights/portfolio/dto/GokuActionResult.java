package com.finsights.portfolio.dto;

/** What a confirmed action did — {@code summary} is the same line the Confirm card showed. */
public record GokuActionResult(String id, String summary, boolean applied) {}
