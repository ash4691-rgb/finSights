package com.finsights.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.CasExtractionResponse;
import com.finsights.portfolio.service.CasImportService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CasImportControllerTest {

    @Mock CasImportService casImport;

    @Test
    void delegatesTheUploadedPdfToTheServiceAndReturnsItsResponse() {
        byte[] pdf = { 1, 2, 3 };
        CasExtractionResponse expected = new CasExtractionResponse(List.of(), "2026-09-30", List.of());
        when(casImport.extract(pdf)).thenReturn(expected);

        CasImportController controller = new CasImportController(casImport);
        CasExtractionResponse actual = controller.importCas(pdf);

        assertThat(actual).isSameAs(expected);
        verify(casImport).extract(pdf);
    }
}
