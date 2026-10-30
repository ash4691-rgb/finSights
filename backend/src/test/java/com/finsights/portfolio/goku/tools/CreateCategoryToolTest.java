package com.finsights.portfolio.goku.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.service.CategoryService;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class CreateCategoryToolTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final Validator validator = Validation.buildDefaultValidatorFactory().getValidator();

    @Mock CategoryService categoryService;

    @Test
    void createsACategoryFromValidInput() throws Exception {
        CreateCategoryTool tool = new CreateCategoryTool(categoryService, mapper, validator);
        JsonNode input = mapper.readTree("{\"name\":\"US Stocks\",\"kind\":\"ASSET\"}");
        CategoryResponse created = mock(CategoryResponse.class);
        when(categoryService.create(new CategoryRequest("US Stocks", HoldingKind.ASSET, null, null, false)))
                .thenReturn(created);

        Object result = tool.execute(input);

        assertThat(result).isEqualTo(created);
    }

    @Test
    void rejectsAMissingName() throws Exception {
        CreateCategoryTool tool = new CreateCategoryTool(categoryService, mapper, validator);
        JsonNode input = mapper.readTree("{\"kind\":\"ASSET\"}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsAMissingKind() throws Exception {
        CreateCategoryTool tool = new CreateCategoryTool(categoryService, mapper, validator);
        JsonNode input = mapper.readTree("{\"name\":\"US Stocks\"}");

        assertThatThrownBy(() -> tool.execute(input)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void passesThroughOptionalFields() throws Exception {
        CreateCategoryTool tool = new CreateCategoryTool(categoryService, mapper, validator);
        JsonNode input = mapper.readTree("{\"name\":\"Home Loan\",\"kind\":\"LIABILITY\",\"description\":\"Our flat\",\"realEstate\":false}");
        ArgumentCaptor<CategoryRequest> captor = ArgumentCaptor.forClass(CategoryRequest.class);
        when(categoryService.create(captor.capture())).thenReturn(mock(CategoryResponse.class));

        tool.execute(input);

        assertThat(captor.getValue().name()).isEqualTo("Home Loan");
        assertThat(captor.getValue().kind()).isEqualTo(HoldingKind.LIABILITY);
        assertThat(captor.getValue().description()).isEqualTo("Our flat");
    }
}
