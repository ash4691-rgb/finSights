package com.finsights.portfolio.dto;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;

public record LayoutResponse(String page, JsonNode config, Instant updatedAt) { }
