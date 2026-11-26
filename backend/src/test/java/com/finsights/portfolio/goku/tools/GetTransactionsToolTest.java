package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TransactionService;
import java.math.BigDecimal;
import java.util.Map;
import java.util.Set;
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
    @Mock HoldingService holdingService;

    @Test
    void parsesFiltersAndDelegatesToTransactionService() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService, holdingService);
        JsonNode input = mapper.readTree(
                "{\"holding_id\":\"h-1\",\"type\":\"buy\",\"broker\":\"zerodha\","
                        + "\"from\":\"2026-01-01\",\"to\":\"2026-03-01\",\"currency\":\"USD\"}");
        when(transactionService.list("h-1", TransactionType.BUY, "zerodha",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), "USD")).thenReturn(List.of());

        Map<?, ?> result = (Map<?, ?>) tool.execute(input);

        assertThat(result.get("transactions")).isEqualTo(List.of());
        assertThat(result.get("count")).isEqualTo(0);
        verify(transactionService).list("h-1", TransactionType.BUY, "zerodha",
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 1), "USD");
    }

    @Test
    void withNoArgumentsListsEverythingUnfiltered() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService, holdingService);
        JsonNode input = mapper.readTree("{}");
        when(transactionService.list(isNull(), isNull(), isNull(), isNull(), isNull(), isNull())).thenReturn(List.of());

        tool.execute(input);

        verify(transactionService).list(null, null, null, null, null, null);
    }

    @Test
    void rejectsAnUnknownTransactionType() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService, holdingService);
        JsonNode input = mapper.readTree("{\"type\":\"FOO\"}");

        assertThatThrownBy(() -> tool.execute(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FOO")
                .hasMessageContaining("BUY");
    }

    @Test
    void rejectsAMalformedDate() throws Exception {
        GetTransactionsTool tool = new GetTransactionsTool(transactionService, holdingService);
        JsonNode input = mapper.readTree("{\"from\":\"not-a-date\"}");

        assertThatThrownBy(() -> tool.execute(input))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YYYY-MM-DD");
    }

    private static TransactionResponse txn(String holdingId, String categoryId, TransactionType type, String amount) {
        return new TransactionResponse("t-" + holdingId + amount, holdingId, "H", categoryId, "C", "Zerodha", "INR",
                type, LocalDate.of(2026, 1, 1), new BigDecimal(amount), null, null, null, null, null);
    }

    @Test
    void filtersByTagViaTheHoldingsTagsAndTotalsPerType() throws Exception {
        HoldingResponse tagged = org.mockito.Mockito.mock(HoldingResponse.class);
        when(tagged.id()).thenReturn("h-1");
        when(tagged.tags()).thenReturn(Set.of("Retirement"));
        HoldingResponse other = org.mockito.Mockito.mock(HoldingResponse.class);
        when(other.tags()).thenReturn(Set.of("trading"));
        when(holdingService.list(null)).thenReturn(List.of(tagged, other));
        when(transactionService.list(null, null, null, null, null, null)).thenReturn(List.of(
                txn("h-1", "c-1", TransactionType.BUY, "100"), txn("h-1", "c-1", TransactionType.BUY, "50"),
                txn("h-1", "c-1", TransactionType.SELL, "30"), txn("h-2", "c-2", TransactionType.BUY, "999")));

        Map<?, ?> result = (Map<?, ?>) new GetTransactionsTool(transactionService, holdingService)
                .execute(mapper.readTree("{\"tag\":\"retirement\"}"));

        assertThat(result.get("count")).isEqualTo(3);
        List<?> totals = (List<?>) result.get("totals");
        assertThat(totals).hasSize(2);
        Map<?, ?> buys = (Map<?, ?>) totals.get(0);
        assertThat(buys.get("type")).isEqualTo(TransactionType.BUY);
        assertThat((BigDecimal) buys.get("amount")).isEqualByComparingTo("150");
    }

    @Test
    void filtersByCategory() throws Exception {
        when(transactionService.list(null, null, null, null, null, null)).thenReturn(List.of(
                txn("h-1", "c-1", TransactionType.BUY, "100"), txn("h-2", "c-2", TransactionType.BUY, "999")));

        Map<?, ?> result = (Map<?, ?>) new GetTransactionsTool(transactionService, holdingService)
                .execute(mapper.readTree("{\"category_id\":\"c-2\"}"));

        assertThat(result.get("count")).isEqualTo(1);
    }
}
