package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.dto.CategoryReorderRequest;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CategoryService {
    private final CategoryRepository categories;
    private final HoldingRepository holdingRepository;
    private final TransactionRepository transactions;
    private final HoldingService holdingService;
    private final CurrentUserService currentUser;
    private final PriceSnapshotService snapshots;

    public CategoryService(CategoryRepository categories, HoldingRepository holdingRepository,
                           TransactionRepository transactions, HoldingService holdingService, CurrentUserService currentUser,
                           PriceSnapshotService snapshots) {
        this.categories = categories;
        this.holdingRepository = holdingRepository;
        this.transactions = transactions;
        this.holdingService = holdingService;
        this.currentUser = currentUser;
        this.snapshots = snapshots;
    }

    public List<CategoryResponse> list(String currency) {
        List<Category> all = categories.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(currentUser.currentUser().getId());
        Map<String, List<HoldingResponse>> byCategory = holdingService.list(currency).stream()
                .collect(Collectors.groupingBy(HoldingResponse::categoryId));
        BigDecimal totalAssets = byCategory.values().stream().flatMap(List::stream)
                .filter(h -> h.kind() == HoldingKind.ASSET).map(HoldingResponse::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalLiabilities = byCategory.values().stream().flatMap(List::stream)
                .filter(h -> h.kind() == HoldingKind.LIABILITY).map(HoldingResponse::currentValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return all.stream()
                .map(c -> toResponse(c, byCategory.getOrDefault(c.getId(), List.of()), totalAssets, totalLiabilities))
                .toList();
    }

    public CategoryResponse get(String id, String currency) {
        return list(currency).stream().filter(c -> c.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
    }

    public CategoryResponse create(CategoryRequest request) {
        Category category = new Category();
        category.setUser(currentUser.currentUser());
        category.setName(request.name().trim());
        category.setKind(request.kind());
        category.setDescription(clean(request.description()));
        category.setSortOrder((int) categories.countByUser_Id(currentUser.currentUser().getId()));
        Category saved = categories.save(category);
        return toResponse(saved, List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** Persists the user's drag-and-drop order. Unknown/foreign ids are ignored; owned categories
     *  missing from the list keep their relative order, appended after the ones given. */
    @Transactional
    public List<CategoryResponse> reorder(CategoryReorderRequest request) {
        String userId = currentUser.currentUser().getId();
        List<Category> owned = categories.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId);
        Map<String, Category> byId = owned.stream()
                .collect(Collectors.toMap(Category::getId, c -> c, (a, b) -> a, java.util.LinkedHashMap::new));
        int position = 0;
        for (String id : request.orderedIds()) {
            Category category = byId.remove(id);
            if (category != null) category.setSortOrder(position++);
        }
        for (Category leftover : byId.values()) {
            leftover.setSortOrder(position++);
        }
        categories.saveAll(owned);
        return list(null);
    }

    public CategoryResponse update(String id, CategoryRequest request) {
        Category category = findOwned(id);
        category.setName(request.name().trim());
        category.setKind(request.kind());
        category.setDescription(clean(request.description()));
        categories.save(category);
        return get(id, null);
    }

    @Transactional
    public void delete(String id) {
        Category category = findOwned(id);
        holdingRepository.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(currentUser.currentUser().getId()).stream()
                .filter(h -> h.getCategory().getId().equals(id))
                .forEach(h -> {
                    transactions.deleteByHolding_Id(h.getId());
                    snapshots.deleteFor(SnapshotSubject.HOLDING, h.getId());
                });
        holdingRepository.deleteByCategory_Id(id);
        categories.delete(category);
    }

    private Category findOwned(String id) {
        return categories.findByIdAndUser_Id(id, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Category not found"));
    }

    private CategoryResponse toResponse(Category c, List<HoldingResponse> holdings, BigDecimal totalAssets, BigDecimal totalLiabilities) {
        BigDecimal invested = sum(holdings, HoldingResponse::investedValue);
        BigDecimal current = sum(holdings, HoldingResponse::currentValue);
        BigDecimal pnl = current.subtract(invested);
        BigDecimal pnlPct = invested.signum() == 0 ? BigDecimal.ZERO
                : pnl.divide(invested, 4, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        BigDecimal denominator = c.getKind() == HoldingKind.LIABILITY ? totalLiabilities : totalAssets;
        BigDecimal weightage = pctOf(current, denominator);
        BigDecimal liquidAmount = sum(holdings, h -> Boolean.TRUE.equals(h.liquidWithinSevenDays()), HoldingResponse::currentValue);
        BigDecimal npaAmount = sum(holdings, h -> Boolean.TRUE.equals(h.blocked()), HoldingResponse::currentValue);
        return new CategoryResponse(c.getId(), c.getName(), c.getKind(), c.getDescription(), invested, current, pnl, pnlPct, weightage,
                liquidAmount, pctOf(liquidAmount, current), npaAmount, pctOf(npaAmount, current),
                holdings.size(), c.getCreatedAt(), c.getUpdatedAt());
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private BigDecimal pctOf(BigDecimal part, BigDecimal whole) {
        return whole == null || whole.signum() == 0 ? BigDecimal.ZERO
                : part.divide(whole, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100)).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal sum(List<HoldingResponse> holdings, Function<HoldingResponse, BigDecimal> getter) {
        return holdings.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal sum(List<HoldingResponse> holdings, Predicate<HoldingResponse> filter, Function<HoldingResponse, BigDecimal> getter) {
        return holdings.stream().filter(filter).map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
