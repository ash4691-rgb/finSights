package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.service.TransactionService;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GetTransactionsToolTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Mock TransactionService transactionService;

    @Test
    void parsesFiltersAndDelegatesToTransactionService() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService);
        JsonNode input = mapper.readTree(
                "{\"holding_id\":\"h-1\",\"type\":\"buy\",\"broker\":\"zerodha\","
                        + "\"from\":\"2026-01-01\",\"to\":\"2026-03-01\",\"currency\":\"USD\"}");
        when(transactionService.list("h-1", TransactionType.BUY, "zerodha",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), "USD")).thenReturn(List.of());

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(List.of());
        verify(transactionService).list("h-1", TransactionType.BUY, "zerodha",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), "USD");
    }

    @Test
    void withNoArgumentsListsEverythingUnfiltered() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService);
        JsonNode input = mapper.readTree("{}");
        when(transactionService.list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(List.of());

        tool.execute(input);

        verify(transactionService).list(null, null, null, null, null, null);
    }

    @Test
    void rejectsAnUnknownTransactionType() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService);
        JsonNode input = mapper.readTree("{\"type\":\"FOO\"}");

        assertThatThrownBy(() -> tool.execute(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FOO")
                .hasMessageContaining("BUY");
    }

    @Test
    void rejectsAMalformedDate() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService);
        JsonNode input = mapper.readTree("{\"from\":\"not-a-date\"}");

        assertThatThrownBy(() -> tool.execute(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }
}
