package com.finsights.portfolio.goku.tools;

import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.JSON;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.VALIDATOR;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.service.CategoryService;
import com.finsights.portfolio.service.HoldingService;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class CreateHoldingToolTest {

    @Mock HoldingService holdingService;
    @Mock CategoryService categoryService;

    private CreateHoldingTool tool() { return new CreateHoldingTool(holdingService, categoryService, JSON, VALIDATOR); }

    @Test
    void previewNamesTheCategoryAndWritesNothing() {
        CategoryResponse category = mock(CategoryResponse.class);
        when(category.name()).thenReturn("Commodities");
        when(categoryService.get("c-1", null)).thenReturn(category);

        String summary = tool().preview(json("{\"categoryId\":\"c-1\",\"name\":\"Gold ETF\",\"broker\":\"Zerodha\"}"));

        assertThat(summary).isEqualTo("Create holding \"Gold ETF\" at Zerodha in category \"Commodities\"");
        verifyNoInteractions(holdingService);
    }

    @Test
    void previewFailsForACategoryThatIsntTheUsers() {
        when(categoryService.get("someone-elses", null)).thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> tool().preview(json("{\"categoryId\":\"someone-elses\",\"name\":\"X\",\"broker\":\"Y\"}")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(holdingService);
    }

    @Test
    void previewRejectsMissingRequiredFields() {
        assertThatThrownBy(() -> tool().preview(json("{\"name\":\"Gold ETF\",\"broker\":\"Zerodha\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool().preview(json("{\"categoryId\":\"c-1\",\"name\":\"Gold ETF\"}"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyParsesFixedRateFieldsIncludingDates() {
        ArgumentCaptor<HoldingRequest> captor = ArgumentCaptor.forClass(HoldingRequest.class);
        when(holdingService.create(captor.capture())).thenReturn(mock(HoldingResponse.class));

        tool().apply(json("""
                {"categoryId":"c-1","name":"Bank FD","broker":"HDFC","valuationMethod":"FIXED_RATE",
                 "fixedAnnualRate":7.1,"compoundingFrequency":"QUARTERLY","fixedRateStartDate":"2026-01-01"}
                """));

        assertThat(captor.getValue().fixedRateStartDate()).isEqualTo(LocalDate.of(2026, 1, 1));
        assertThat(captor.getValue().fixedAnnualRate()).isEqualByComparingTo("7.1");
    }
}
