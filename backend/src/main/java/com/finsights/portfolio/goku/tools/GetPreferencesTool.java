package com.finsights.portfolio.goku.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.finsights.portfolio.dto.SettingsResponse;
import com.finsights.portfolio.goku.GokuTool;
import com.finsights.portfolio.service.PersonaService;
import com.finsights.portfolio.service.SettingsService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Platform-owned data, wrapped for Goku — the signed-in user's own profile, display preferences
 * and investor persona, read through the same {@code SettingsService}/{@code PersonaService}
 * calls the Settings page uses. Only the fields a preference conversation needs are passed on:
 * internal flags (onboarding dismissals, demo mode) stay out of the model's context.
 */
@Component
class GetPreferencesTool implements GokuTool {
    private final SettingsService settings;
    private final PersonaService persona;

    GetPreferencesTool(SettingsService settings, PersonaService persona) {
        this.settings = settings;
        this.persona = persona;
    }

    @Override public String name() { return "get_preferences"; }

    @Override public String description() {
        return "Returns the signed-in user's profile and preferences: display name, phone, country and base currency "
                + "(base currency always follows country), number format, notification settings, and their investor "
                + "persona (age, occupation, salary range, portfolio size, investing tenure, risk profile) if set up.";
    }

    @Override public Map<String, Object> inputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override public Object execute(JsonNode input) {
        SettingsResponse s = settings.current();
        Map<String, Object> profile = new LinkedHashMap<>();
        profile.put("displayName", s.displayName());
        profile.put("phone", s.phone());
        profile.put("country", s.country());
        profile.put("countryName", s.countryName());
        profile.put("baseCurrency", s.baseCurrency());
        profile.put("numberFormat", s.numberFormat());
        profile.put("notifyEmail", s.notifyEmail());
        profile.put("notifySms", s.notifySms());
        profile.put("notifyPush", s.notifyPush());
        profile.put("notifyThresholdPercent", s.notifyThresholdPercent());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("profile", profile);
        result.put("persona", persona.current().orElse(null));
        return result;
    }
}
