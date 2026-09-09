package com.finsights.portfolio.dto;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotNull;

public record LayoutUpdateRequest(@NotNull JsonNode config) { }
