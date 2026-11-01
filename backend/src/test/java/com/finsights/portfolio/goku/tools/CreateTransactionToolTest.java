package com.finsights.portfolio.goku.tools;

import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.JSON;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.VALIDATOR;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TransactionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateTransactionToolTest {

    @Mock TransactionService transactionService;
    @Mock HoldingService holdingService;

    private CreateTransactionTool tool() { return new CreateTransactionTool(transactionService, holdingService, JSON, VALIDATOR); }

    private void holdingIs(String id, String name, String currency) {
        HoldingResponse holding = mock(HoldingResponse.class);
        when(holding.name()).thenReturn(name);
        when(holding.defaultCurrency()).thenReturn(currency);
        when(holdingService.get(id)).thenReturn(holding);
    }

    @Test
    void previewDescribesABuyInTheHoldingsOwnCurrency() {
        holdingIs("h-1", "Reliance", "INR");

        String summary = tool().preview(json("{\"holdingId\":\"h-1\",\"type\":\"BUY\",\"date\":\"2026-02-01\",\"amount\":5000,\"quantity\":10}"));

        assertThat(summary).isEqualTo("Log BUY of 5000 INR (10 units) on 2026-02-01 for \"Reliance\"");
        verifyNoInteractions(transactionService);
    }

    @Test
    void previewDescribesASplitByItsMultiplier() {
        HoldingResponse holding = mock(HoldingResponse.class);
        when(holding.name()).thenReturn("Infosys");
        when(holdingService.get("h-1")).thenReturn(holding);

        assertThat(tool().preview(json("{\"holdingId\":\"h-1\",\"type\":\"SPLIT\",\"date\":\"2026-02-01\",\"amount\":0,\"quantity\":2}")))
                .isEqualTo("Log SPLIT of a 2-for-1 split on 2026-02-01 for \"Infosys\"");
    }

    @Test
    void previewRejectsAMissingAmountOrUnknownType() {
        assertThatThrownBy(() -> tool().preview(json("{\"holdingId\":\"h-1\",\"type\":\"SPLIT\",\"date\":\"2026-02-01\"}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool().preview(json("{\"holdingId\":\"h-1\",\"type\":\"FOO\",\"date\":\"2026-02-01\",\"amount\":1}")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void applyCreates() {
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        when(transactionService.create(captor.capture())).thenReturn(mock(TransactionResponse.class));

        tool().apply(json("{\"holdingId\":\"h-1\",\"type\":\"BUY\",\"date\":\"2026-02-01\",\"amount\":5000}"));

        assertThat(captor.getValue().type()).isEqualTo(TransactionType.BUY);
        assertThat(captor.getValue().amount()).isEqualByComparingTo("5000");
    }
}
