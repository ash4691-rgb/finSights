package com.finsights.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.dto.TaxReportResponse;
import com.finsights.portfolio.service.TaxService;
import org.junit.jupiter.api.Test;

class TaxControllerTest {
    @Test
    void delegatesReadsAndReturnsTheFreshReportAfterAnOverride() {
        TaxService service = mock(TaxService.class);
        TaxReportResponse report = mock(TaxReportResponse.class);
        when(service.report("2025-26")).thenReturn(report);
        TaxController controller = new TaxController(service);

        assertThat(controller.report("2025-26")).isSameAs(report);
        assertThat(controller.setTaxClass("h-1", new TaxController.TaxClassRequest(TaxClass.EXEMPT), "2025-26")).isSameAs(report);
        verify(service).setTaxClass("h-1", TaxClass.EXEMPT);
    }
}
