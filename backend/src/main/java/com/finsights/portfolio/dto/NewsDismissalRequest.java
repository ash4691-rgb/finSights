package com.finsights.portfolio.dto;

import jakarta.validation.constraints.NotBlank;

/** Crosses one specific article (identified by its link) off the current user's Radar. */
public record NewsDismissalRequest(@NotBlank String link) { }
