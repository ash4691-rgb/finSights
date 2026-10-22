package com.finsights.portfolio.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.StatementExtractionResponse;
import com.finsights.portfolio.dto.StatementExtractionResponse.StatementType;
import com.finsights.portfolio.service.StatementImportService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StatementImportControllerTest {

    @Mock StatementImportService statementImport;

    @Test
    void delegatesTheUploadedFileAndPasswordToTheServiceAndReturnsItsResponse() {
        byte[] file = { 1, 2, 3 };
        StatementExtractionResponse expected = new StatementExtractionResponse(
                StatementType.HOLDINGS, List.of(), List.of(), List.of(), "2026-09-30", List.of());
        when(statementImport.extract(file, "ABCDE1234F")).thenReturn(expected);

        StatementImportController controller = new StatementImportController(statementImport);
        StatementExtractionResponse actual = controller.importStatement(file, "ABCDE1234F");

        assertThat(actual).isSameAs(expected);
        verify(statementImport).extract(file, "ABCDE1234F");
    }
}
