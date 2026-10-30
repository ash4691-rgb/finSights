package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** The same ObjectMapper shape Spring Boot hands the tools in production (ISO dates, not timestamps). */
final class GokuToolTestSupport {
    static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    private GokuToolTestSupport() {}

    static JsonNode json(String raw) {
        try { return JSON.readTree(raw); } catch (Exception e) { throw new IllegalArgumentException(e); }
    }
}
