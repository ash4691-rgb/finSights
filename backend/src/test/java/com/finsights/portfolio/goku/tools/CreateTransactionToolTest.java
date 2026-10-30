package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.service.TransactionService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateTransactionToolTest {

    private final ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock TransactionService transactionService;

    @Test
    void createsABuyFromValidInput() throws Exception {
        CreateTransactionTool tool = new CreateTransactionTool(transactionService, mapper, validator);
        JsonNode input = mapper.readTree("{\"holdingId\":\"h-1\",\"type\":\"BUY\",\"date\":\"2026-02-01\",\"amount\":5000,\"quantity\":10}");
        TransactionResponse created = mock(TransactionResponse.class);
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        when(transactionService.create(captor.capture())).thenReturn(created);

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(created);
        assertThat(captor.getValue().holdingId()).isEqualTo("h-1");
        assertThat(captor.getValue().type()).isEqualTo(TransactionType.BUY);
        assertThat(captor.getValue().date()).isEqualTo(LocalDate.of(2026, 2, 1));
        assertThat(captor.getValue().amount()).isEqualByComparingTo("5000");
    }

    @Test
    void rejectsAMissingHoldingId() throws Exception {
        CreateTransactionTool tool = new CreateTransactionTool(transactionService, mapper, validator);
        JsonNode input = mapper.readTree("{\"type\":\"BUY\",\"date\":\"2026-02-01\",\"amount\":5000}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingAmount() throws Exception {
        CreateTransactionTool tool = new CreateTransactionTool(transactionService, mapper, validator);
        JsonNode input = mapper.readTree("{\"holdingId\":\"h-1\",\"type\":\"SPLIT\",\"date\":\"2026-02-01\"}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void acceptsASplitWithZeroAmountAndAMultiplierQuantity() throws Exception {
        CreateTransactionTool tool = new CreateTransactionTool(transactionService, mapper, validator);
        JsonNode input = mapper.readTree("{\"holdingId\":\"h-1\",\"type\":\"SPLIT\",\"date\":\"2026-02-01\",\"amount\":0,\"quantity\":2}");
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        when(transactionService.create(captor.capture())).thenReturn(mock(TransactionResponse.class));

        tool.execute(input);

        assertThat(captor.getValue().type()).isEqualTo(TransactionType.SPLIT);
        assertThat(captor.getValue().quantity()).isEqualByComparingTo("2");
    }

    @Test
    void rejectsAnUnknownTransactionType() throws Exception {
        CreateTransactionTool tool = new CreateTransactionTool(transactionService, mapper, validator);
        JsonNode input = mapper.readTree("{\"holdingId\":\"h-1\",\"type\":\"FOO\",\"date\":\"2026-02-01\",\"amount\":1}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }
}
