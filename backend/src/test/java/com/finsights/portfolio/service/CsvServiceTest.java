package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.dto.ImportResultResponse;
import com.finsights.portfolio.dto.TransactionRequest;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CsvServiceTest {

    @Mock TransactionService transactionService;

    @Test
    void parsesQuotedFieldsWithCommasAndEscapedQuotes() {
        List<List<String>> rows = CsvService.parse("holdingId,notes\r\nh-1,\"He said \"\"hi\"\", bought more\"\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsExactly("h-1", "He said \"hi\", bought more");
    }

    @Test
    void importCreatesNewRowAndUpdatesExistingById() {
        CsvService csv = new CsvService(transactionService);
        String body = String.join("\n",
                "id,holdingId,type,date,amount,quantity,notes",
                ",h-1,BUY,2026-01-15,50000,10,Initial buy",
                "t-9,h-2,CONTRIBUTION_TYPO,2024-06-01,200000,,");

        // second row is intentionally invalid (bad type) to also exercise the error path
        ImportResultResponse result = csv.importCsv(body);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);

        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(transactionService).create(captor.capture());
        assertThat(captor.getValue().holdingId()).isEqualTo("h-1");
        assertThat(captor.getValue().notes()).isEqualTo("Initial buy");
    }

    @Test
    void importUpdatesExistingTransactionWhenIdPresent() {
        CsvService csv = new CsvService(transactionService);
        String body = String.join("\n",
                "id,holdingId,type,date,amount",
                "t-1,h-1,SELL,2026-03-01,20000");

        csv.importCsv(body);

        verify(transactionService).update(eq("t-1"), any());
    }

    @Test
    void importReportsRowErrorsWithoutStopping() {
        CsvService csv = new CsvService(transactionService);
        String body = String.join("\n",
                "holdingId,type,date,amount",
                "h-1,NOT_A_TYPE,2026-01-01,1",
                "h-2,BUY,2026-01-02,2");

        ImportResultResponse result = csv.importCsv(body);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.skipped()).isEqualTo(1);
        assertThat(result.errors()).hasSize(1);
        assertThat(result.errors().get(0).row()).isEqualTo(2);
    }
}
