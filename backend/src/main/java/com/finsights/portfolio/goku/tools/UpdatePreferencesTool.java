package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.dto.CountryResponse;
import com.finsights.portfolio.dto.SettingsRequest;
import com.finsights.portfolio.goku.GokuWriteTool;
import com.finsights.portfolio.service.CountryCurrencyService;
import com.finsights.portfolio.service.SettingsService;
import jakarta.validation.Validator;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Platform-owned write — {@link SettingsService#update}, patch-style (see {@link GokuArgs#overlay}).
 * The overlay matters more here than anywhere: {@code update} always overwrites the Hot-picks
 * thresholds from the request, null included, so a whole-record resend that left them out would
 * silently switch them all off. Overlaying onto {@code current()} carries them through untouched.
 */
@Component
class UpdatePreferencesTool implements GokuWriteTool {
    private final SettingsService settings;
    private final CountryCurrencyService countries;
    private final ObjectMapper json;
    private final Validator validator;

    UpdatePreferencesTool(SettingsService settings, CountryCurrencyService countries, ObjectMapper sharedJson, Validator validator) {
        this.settings = settings;
        this.countries = countries;
        this.json = sharedJson;
        this.validator = validator;
    }

    @Override public String name() { return "update_preferences"; }

    @Override public String description() {
        return "Changes the signed-in user's profile or display preferences. Send only the fields to change. Base "
                + "currency can't be set directly — it always follows country, so to change base currency change "
                + "country (e.g. US for USD). This only proposes the change: the user confirms it with a button.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of(
                "country", Map.of("type", "string", "description", "ISO-3166 alpha-2 code, e.g. IN, US, GB, SG, AE. Sets base currency."),
                "displayName", Map.of("type", "string"),
                "phone", Map.of("type", "string"),
                "numberFormat", Map.of("type", "string", "enum", List.of("INDIAN", "INTERNATIONAL"),
                        "description", "INDIAN = lakh/crore grouping, INTERNATIONAL = million/billion."),
                "notifyEmail", Map.of("type", "boolean"),
                "notifySms", Map.of("type", "boolean"),
                "notifyPush", Map.of("type", "boolean"),
                "notifyThresholdPercent", Map.of("type", "number", "description", "Alert when a holding moves by this %.")));
    }

    @Override public String preview(JsonNode input) {
        GokuArgs.overlay(json, validator, settings.current(), input, SettingsRequest.class);
        String changes = GokuArgs.describeChanges(input);
        String country = GokuArgs.text(input, "country");
        if (country != null) {
            CountryResponse resolved = countries.require(country);
            changes += " (" + resolved.name() + " — base currency becomes " + resolved.currency() + ")";
        }
        return "Update your preferences: " + changes;
    }

    @Override public Object apply(JsonNode input) {
        return settings.update(GokuArgs.overlay(json, validator, settings.current(), input, SettingsRequest.class));
    }
}
