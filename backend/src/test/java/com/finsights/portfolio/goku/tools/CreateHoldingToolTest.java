package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.service.HoldingService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateHoldingToolTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock HoldingService holdingService;

    @Test
    void createsAManualHoldingFromValidInput() throws Exception {
        CreateHoldingTool tool = new CreateHoldingTool(holdingService, mapper, validator);
        JsonNode input = mapper.readTree("{\"categoryId\":\"c-1\",\"name\":\"Gold ETF\",\"broker\":\"Zerodha\"}");
        HoldingResponse created = mock(HoldingResponse.class);
        ArgumentCaptor<HoldingRequest> captor = ArgumentCaptor.forClass(HoldingRequest.class);
        when(holdingService.create(captor.capture())).thenReturn(created);

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(created);
        assertThat(captor.getValue().categoryId()).isEqualTo("c-1");
        assertThat(captor.getValue().name()).isEqualTo("Gold ETF");
        assertThat(captor.getValue().broker()).isEqualTo("Zerodha");
    }

    @Test
    void rejectsAMissingCategoryId() throws Exception {
        CreateHoldingTool tool = new CreateHoldingTool(holdingService, mapper, validator);
        JsonNode input = mapper.readTree("{\"name\":\"Gold ETF\",\"broker\":\"Zerodha\"}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingBroker() throws Exception {
        CreateHoldingTool tool = new CreateHoldingTool(holdingService, mapper, validator);
        JsonNode input = mapper.readTree("{\"categoryId\":\"c-1\",\"name\":\"Gold ETF\"}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void parsesFixedRateFieldsIncludingDates() throws Exception {
        CreateHoldingTool tool = new CreateHoldingTool(holdingService, mapper, validator);
        JsonNode input = mapper.readTree("""
                {"categoryId":"c-1","name":"Bank FD","broker":"HDFC","valuationMethod":"FIXED_RATE",
                 "fixedAnnualRate":7.1,"compoundingFrequency":"QUARTERLY","fixedRateStartDate":"2026-01-01"}
                """);
        ArgumentCaptor<HoldingRequest> captor = ArgumentCaptor.forClass(HoldingRequest.class);
        when(holdingService.create(captor.capture())).thenReturn(mock(HoldingResponse.class));

        tool.execute(input);

        assertThat(captor.getValue().fixedRateStartDate()).isEqualTo(java.time.LocalDate.of(2026, 1, 1));
        assertThat(captor.getValue().fixedAnnualRate()).isEqualByComparingTo("7.1");
    }
}
