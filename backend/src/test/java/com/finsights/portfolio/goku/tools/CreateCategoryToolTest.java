package com.finsights.portfolio.goku.tools;

import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.JSON;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.VALIDATOR;
import static com.finsights.portfolio.goku.tools.GokuToolTestSupport.json;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.service.CategoryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateCategoryToolTest {

    @Mock CategoryService categoryService;

    private CreateCategoryTool tool() { return new CreateCategoryTool(categoryService, JSON, VALIDATOR); }

    @Test
    void previewDescribesTheChangeWithoutWriting() {
        assertThat(tool().preview(json("{\"name\":\"US Stocks\",\"kind\":\"ASSET\"}"))).isEqualTo("Create asset category \"US Stocks\"");
        verifyNoInteractions(categoryService);
    }

    @Test
    void executeIsNeverAllowedForAWriteTool() {
        assertThatThrownBy(() -> tool().execute(json("{\"name\":\"US Stocks\",\"kind\":\"ASSET\"}")))
                .isInstanceOf(UnsupportedOperationException.class);
        verifyNoInteractions(categoryService);
    }

    @Test
    void applyCreates() {
        CategoryResponse created = mock(CategoryResponse.class);
        when(categoryService.create(new CategoryRequest("US Stocks", HoldingKind.ASSET, null, null, false))).thenReturn(created);

        assertThat(tool().apply(json("{\"name\":\"US Stocks\",\"kind\":\"ASSET\"}"))).isEqualTo(created);
    }

    @Test
    void previewRejectsAMissingNameOrKind() {
        assertThatThrownBy(() -> tool().preview(json("{\"kind\":\"ASSET\"}"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> tool().preview(json("{\"name\":\"US Stocks\"}"))).isInstanceOf(IllegalArgumentException.class);
    }
}
