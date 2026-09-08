package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.TagSuggestion;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TagSuggestionRepository;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class TagSuggestionServiceTest {

    @Mock TagSuggestionRepository suggestions;
    @Mock HoldingRepository holdings;
    @Mock CurrentUserService currentUser;

    private TagSuggestionService service;
    private final UserAccount user = new UserAccount("demo@finsights.local", "Demo");

    @BeforeEach
    void setUp() throws Exception {
        setId(user, "u-1");
        lenient().when(currentUser.currentUser()).thenReturn(user);
        lenient().when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(any())).thenReturn(List.of());
        service = new TagSuggestionService(suggestions, holdings, currentUser);
    }

    @Test
    void recordCreatesAndIncrementsPerContext() {
        when(suggestions.findByUser_IdAndTagAndCategoryIdAndValuationMethod("u-1", "retirement", "c-1", ValuationMethod.FIXED_RATE))
                .thenReturn(Optional.empty());

        service.record("c-1", ValuationMethod.FIXED_RATE, List.of(" Retirement ", ""));

        ArgumentCaptor<TagSuggestion> saved = ArgumentCaptor.forClass(TagSuggestion.class);
        verify(suggestions).save(saved.capture());
        assertThat(saved.getValue().getTag()).isEqualTo("retirement");
        assertThat(saved.getValue().getCategoryId()).isEqualTo("c-1");
        assertThat(saved.getValue().getValuationMethod()).isEqualTo(ValuationMethod.FIXED_RATE);
        assertThat(saved.getValue().getUsageCount()).isEqualTo(1);
    }

    @Test
    void recordIsANoOpWithoutCategoryOrMethod() {
        service.record(null, ValuationMethod.MANUAL, List.of("x"));
        service.record("c-1", null, List.of("x"));
        verify(suggestions, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void suggestRanksExactContextMatchesFirst() {
        when(suggestions.findByUser_IdOrderByUsageCountDescUpdatedAtDesc("u-1")).thenReturn(List.of(
                row("global", null, null, 50),
                row("category-only", "c-1", ValuationMethod.MANUAL, 1),
                row("exact", "c-1", ValuationMethod.FIXED_RATE, 1)));

        List<String> result = service.suggest("c-1", ValuationMethod.FIXED_RATE);

        assertThat(result).containsExactly("exact", "category-only", "global");
    }

    @Test
    void suggestSeedsFromExistingHoldingTags() throws Exception {
        when(suggestions.findByUser_IdOrderByUsageCountDescUpdatedAtDesc("u-1")).thenReturn(List.of());
        Category category = new Category();
        setId(category, "c-1");
        Holding holding = new Holding();
        holding.setCategory(category);
        holding.setValuationMethod(ValuationMethod.FIXED_RATE);
        holding.setTags(Set.of("Tax-Saver"));
        when(holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc("u-1")).thenReturn(List.of(holding));

        assertThat(service.suggest("c-1", ValuationMethod.FIXED_RATE)).containsExactly("tax-saver");
    }

    private static void setId(Object entity, String id) throws Exception {
        Field field = entity.getClass().getDeclaredField("id");
        field.setAccessible(true);
        field.set(entity, id);
    }

    private TagSuggestion row(String tag, String categoryId, ValuationMethod method, int count) {
        TagSuggestion t = new TagSuggestion();
        t.setTag(tag);
        t.setCategoryId(categoryId);
        t.setValuationMethod(method);
        t.setUsageCount(count);
        return t;
    }
}
