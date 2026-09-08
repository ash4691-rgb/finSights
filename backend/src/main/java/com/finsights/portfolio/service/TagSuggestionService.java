package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.TagSuggestion;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TagSuggestionRepository;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Remembers which tags a user applies to which kind of holding and, on the way
 * back, ranks tags for the add/edit form by how well they fit the holding's
 * category and valuation method.
 */
@Service
public class TagSuggestionService {

    private static final int DEFAULT_LIMIT = 8;

    private final TagSuggestionRepository suggestions;
    private final HoldingRepository holdings;
    private final CurrentUserService currentUser;

    public TagSuggestionService(TagSuggestionRepository suggestions, HoldingRepository holdings,
                                CurrentUserService currentUser) {
        this.suggestions = suggestions;
        this.holdings = holdings;
        this.currentUser = currentUser;
    }

    /** Records every tag on a saved holding against that holding's category and valuation method. */
    @Transactional
    public void record(String categoryId, ValuationMethod valuationMethod, Collection<String> tags) {
        if (tags == null || tags.isEmpty() || categoryId == null || valuationMethod == null) return;
        UserAccount user = currentUser.currentUser();
        Instant now = Instant.now();
        for (String raw : tags) {
            String tag = normalize(raw);
            if (tag.isEmpty()) continue;
            TagSuggestion row = suggestions
                    .findByUser_IdAndTagAndCategoryIdAndValuationMethod(user.getId(), tag, categoryId, valuationMethod)
                    .orElseGet(() -> {
                        TagSuggestion fresh = new TagSuggestion();
                        fresh.setUser(user);
                        fresh.setTag(tag);
                        fresh.setCategoryId(categoryId);
                        fresh.setValuationMethod(valuationMethod);
                        return fresh;
                    });
            row.setUsageCount(row.getUsageCount() + 1);
            row.setUpdatedAt(now);
            suggestions.save(row);
        }
    }

    @Transactional(readOnly = true)
    public List<String> suggest(String categoryId, ValuationMethod valuationMethod) {
        return suggest(categoryId, valuationMethod, DEFAULT_LIMIT);
    }

    @Transactional(readOnly = true)
    public List<String> suggest(String categoryId, ValuationMethod valuationMethod, int limit) {
        int cap = Math.max(1, Math.min(limit, 100));
        String userId = currentUser.currentUser().getId();
        Map<String, Integer> scored = new LinkedHashMap<>();

        for (TagSuggestion row : suggestions.findByUser_IdOrderByUsageCountDescUpdatedAtDesc(userId)) {
            scored.merge(row.getTag(),
                    contextScore(categoryId, valuationMethod, row.getCategoryId(), row.getValuationMethod())
                            + row.getUsageCount(),
                    Math::max);
        }
        // Seed from tags already on holdings so suggestions are useful before the
        // first save under this feature.
        for (Holding holding : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)) {
            String holdingCategoryId = holding.getCategory() != null ? holding.getCategory().getId() : null;
            int score = contextScore(categoryId, valuationMethod, holdingCategoryId, holding.getValuationMethod());
            for (String raw : holding.getTags()) {
                String tag = normalize(raw);
                if (!tag.isEmpty()) scored.merge(tag, score, Math::max);
            }
        }

        return scored.entrySet().stream()
                .sorted((a, b) -> b.getValue() - a.getValue())
                .map(Map.Entry::getKey)
                .limit(cap)
                .toList();
    }

    private static int contextScore(String wantCategory, ValuationMethod wantMethod,
                                    String haveCategory, ValuationMethod haveMethod) {
        boolean categoryMatch = wantCategory != null && wantCategory.equals(haveCategory);
        boolean methodMatch = wantMethod != null && wantMethod == haveMethod;
        if (categoryMatch && methodMatch) return 1000;
        if (categoryMatch) return 500;
        if (methodMatch) return 100;
        return 0;
    }

    private static String normalize(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }
}
