package com.finsights.portfolio.goku.tools;

import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.JSON;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.VALIDATOR;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.service.CategoryService;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TransactionService;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class UpdateAndDeleteToolsTest {

    @Mock CategoryService categoryService;
    @Mock HoldingService holdingService;
    @Mock TransactionService transactionService;

    private static CategoryResponse category(String id, String name, int holdingCount) {
        return new CategoryResponse(id, name, HoldingKind.ASSET, "Long-term", null, false,
                null, null, null, null, null, null, null, null, null, holdingCount, null, null);
    }

    private static TransactionResponse buy() {
        return new TransactionResponse("t-1", "h-1", "Reliance", "c-1", "Stocks", "Zerodha", "INR",
                TransactionType.BUY, LocalDate.of(2026, 1, 5), new BigDecimal("5000"), new BigDecimal("2"),
                null, null, "first lot", null);
    }

    @Test
    void updateCategoryOnlyChangesTheFieldsSentAndKeepsTheRest() {
        when(categoryService.get("c-1", null)).thenReturn(category("c-1", "Stocks", 3));
        ArgumentCaptor<CategoryRequest> captor = ArgumentCaptor.forClass(CategoryRequest.class);
        UpdateCategoryTool tool = new UpdateCategoryTool(categoryService, JSON, VALIDATOR);

        String summary = tool.preview(json("{\"id\":\"c-1\",\"name\":\"Indian Stocks\"}"));
        tool.apply(json("{\"id\":\"c-1\",\"name\":\"Indian Stocks\"}"));

        assertThat(summary).isEqualTo("Update category \"Stocks\": name → \"Indian Stocks\"");
        verify(categoryService).update(eq("c-1"), captor.capture());
        assertThat(captor.getValue().name()).isEqualTo("Indian Stocks");
        assertThat(captor.getValue().description()).isEqualTo("Long-term");
        assertThat(captor.getValue().kind()).isEqualTo(HoldingKind.ASSET);
    }

    @Test
    void updateRejectsAnEmptyChangeAndAnUnknownField() {
        when(categoryService.get("c-1", null)).thenReturn(category("c-1", "Stocks", 3));
        UpdateCategoryTool tool = new UpdateCategoryTool(categoryService, JSON, VALIDATOR);

        assertThatThrownBy(() -> tool.preview(json("{\"id\":\"c-1\"}"))).hasMessageContaining("No changes");
        assertThatThrownBy(() -> tool.preview(json("{\"id\":\"c-1\",\"userId\":\"someone-else\"}"))).hasMessageContaining("Unknown field");
    }

    @Test
    void updateTransactionKeepsUntouchedFieldsIncludingTheDate() {
        when(transactionService.get("t-1")).thenReturn(buy());
        ArgumentCaptor<TransactionRequest> captor = ArgumentCaptor.forClass(TransactionRequest.class);
        UpdateTransactionTool tool = new UpdateTransactionTool(transactionService, holdingService, JSON, VALIDATOR);

        tool.apply(json("{\"id\":\"t-1\",\"amount\":5200}"));

        verify(transactionService).update(eq("t-1"), captor.capture());
        assertThat(captor.getValue().amount()).isEqualByComparingTo("5200");
        assertThat(captor.getValue().date()).isEqualTo(LocalDate.of(2026, 1, 5));
        assertThat(captor.getValue().quantity()).isEqualByComparingTo("2");
        assertThat(captor.getValue().notes()).isEqualTo("first lot");
    }

    @Test
    void deleteCategorySpellsOutTheCascadeAndIsDestructive() {
        when(categoryService.get("c-1", null)).thenReturn(category("c-1", "Stocks", 3));
        DeleteCategoryTool tool = new DeleteCategoryTool(categoryService);

        assertThat(tool.destructive()).isTrue();
        assertThat(tool.preview(json("{\"id\":\"c-1\"}")))
                .isEqualTo("Delete category \"Stocks\" — this also deletes its 3 holdings and all their transactions. This can't be undone.");
        verify(categoryService, never()).delete(any());
    }

    @Test
    void deleteTransactionPreviewWritesNothingAndApplyDeletes() {
        when(transactionService.get("t-1")).thenReturn(buy());
        DeleteTransactionTool tool = new DeleteTransactionTool(transactionService);

        assertThat(tool.preview(json("{\"id\":\"t-1\"}")))
                .isEqualTo("Delete the BUY of 5000 INR on 2026-01-05 for \"Reliance\". This can't be undone.");
        verify(transactionService, never()).delete(any());

        tool.apply(json("{\"id\":\"t-1\"}"));
        verify(transactionService).delete("t-1");
    }

    @Test
    void deleteHoldingCountsItsTransactions() {
        com.finsights.portfolio.dto.HoldingResponse holding = mock(com.finsights.portfolio.dto.HoldingResponse.class);
        when(holding.name()).thenReturn("Reliance");
        when(holding.broker()).thenReturn("Zerodha");
        when(holdingService.get("h-1")).thenReturn(holding);
        when(transactionService.list("h-1", null, null, null, null, null)).thenReturn(java.util.List.of(buy(), buy()));

        assertThat(new DeleteHoldingTool(holdingService, transactionService).preview(json("{\"id\":\"h-1\"}")))
                .isEqualTo("Delete holding \"Reliance\" (Zerodha) and its 2 transactions. This can't be undone.");
    }
}
