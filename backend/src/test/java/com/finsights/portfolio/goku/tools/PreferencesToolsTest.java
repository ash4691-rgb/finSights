package com.finsights.portfolio.goku.tools;

import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.JSON;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.VALIDATOR;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.CountryResponse;
import com.finsights.portfolio.dto.SettingsRequest;
import com.finsights.portfolio.dto.SettingsResponse;
import com.finsights.portfolio.service.CountryCurrencyService;
import com.finsights.portfolio.service.PersonaService;
import com.finsights.portfolio.service.SettingsService;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PreferencesToolsTest {

    @Mock SettingsService settingsService;
    @Mock CountryCurrencyService countries;
    @Mock PersonaService personaService;

    private static SettingsResponse current() {
        return new SettingsResponse("me@x.com", "Ash", "99999", "IN", "India", "INR", "INDIAN", true, false, false,
                new BigDecimal("5"), new BigDecimal("3"), new BigDecimal("6"), null, null, new BigDecimal("25"),
                false, 4, null, true, true, true, true);
    }

    @Test
    void changingCountryPreviewsTheNewBaseCurrency() {
        when(settingsService.current()).thenReturn(current());
        when(countries.require("US")).thenReturn(new CountryResponse("US", "United States", "USD"));

        String summary = new UpdatePreferencesTool(settingsService, countries, JSON, VALIDATOR).preview(json("{\"country\":\"US\"}"));

        assertThat(summary).isEqualTo("Update your preferences: country → \"US\" (United States — base currency becomes USD)");
        verify(settingsService, never()).update(any());
    }

    @Test
    void applyCarriesHotPickThresholdsThroughInsteadOfWipingThem() {
        when(settingsService.current()).thenReturn(current());
        ArgumentCaptor<SettingsRequest> captor = ArgumentCaptor.forClass(SettingsRequest.class);

        new UpdatePreferencesTool(settingsService, countries, JSON, VALIDATOR).apply(json("{\"displayName\":\"Ashish\"}"));

        verify(settingsService).update(captor.capture());
        SettingsRequest sent = captor.getValue();
        assertThat(sent.displayName()).isEqualTo("Ashish");
        assertThat(sent.country()).isEqualTo("IN");
        assertThat(sent.dailyThresholdPercent()).isEqualByComparingTo("3");
        assertThat(sent.weeklyThresholdPercent()).isEqualByComparingTo("6");
        assertThat(sent.yearlyThresholdPercent()).isEqualByComparingTo("25");
    }

    @Test
    void riskAnswersMustBeInRangeForTheirOwnQuestion() {
        UpdateRiskProfileTool tool = new UpdateRiskProfileTool(personaService, JSON, VALIDATOR);

        // Risk tolerance has only three options (0-2).
        assertThatThrownBy(() -> tool.preview(json("""
                {"timeHorizonAnswer":3,"riskCapacityAnswer":4,"riskToleranceAnswer":3,
                 "investmentObjectivesAnswer":1,"liquidityNeedsAnswer":0}""")))
                .hasMessageContaining("riskToleranceAnswer");

        assertThat(tool.preview(json("""
                {"timeHorizonAnswer":3,"riskCapacityAnswer":4,"riskToleranceAnswer":2,
                 "investmentObjectivesAnswer":1,"liquidityNeedsAnswer":0}""")))
                .startsWith("Recompute your risk profile from these answers: More than 10 years;");
        verify(personaService, never()).updateRisk(any());
    }
}
