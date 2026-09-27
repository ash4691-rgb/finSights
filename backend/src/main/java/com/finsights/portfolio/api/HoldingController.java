package com.finsights.portfolio.api;

import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingReorderRequest;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.ValuationDetailResponse;
import com.finsights.portfolio.service.HoldingService;
import com.finsights.portfolio.service.TagSuggestionService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/holdings")
public class HoldingController {
    private final HoldingService holdings;
    private final TagSuggestionService tagSuggestions;

    public HoldingController(HoldingService holdings, TagSuggestionService tagSuggestions) {
        this.holdings = holdings;
        this.tagSuggestions = tagSuggestions;
    }

    @GetMapping
    List<HoldingResponse> list(@RequestParam(required = false) String categoryId, @RequestParam(required = false) String currency) {
        return categoryId != null && !categoryId.isBlank()
                ? holdings.listByCategory(categoryId, currency)
                : holdings.list(currency);
    }

    @GetMapping("/tag-suggestions")
    List<String> suggestTags(@RequestParam(required = false) String categoryId,
                             @RequestParam(required = false) ValuationMethod valuationMethod,
                             @RequestParam(required = false, defaultValue = "8") int limit) {
        return tagSuggestions.suggest(categoryId, valuationMethod, limit);
    }

    // currency is optional and native (unconverted) when omitted — HoldingModal's own edit-form
    // fetch relies on exactly that native default (see its comment on nativeHolding) to never
    // resave a display-converted figure, so this default must not change.
    @GetMapping("/{id}")
    HoldingResponse get(@PathVariable String id, @RequestParam(required = false) String currency) {
        return holdings.get(id, currency);
    }

    @GetMapping("/{id}/valuation")
    ValuationDetailResponse valuation(@PathVariable String id) { return holdings.valuationDetail(id); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    HoldingResponse create(@Valid @RequestBody HoldingRequest request, @RequestParam(required = false) String currency) {
        HoldingResponse saved = holdings.create(request, currency);
        tagSuggestions.record(saved.categoryId(), saved.valuationMethod(), saved.tags());
        return saved;
    }

    @PutMapping("/{id}")
    HoldingResponse update(@PathVariable String id, @Valid @RequestBody HoldingRequest request, @RequestParam(required = false) String currency) {
        HoldingResponse saved = holdings.update(id, request, currency);
        tagSuggestions.record(saved.categoryId(), saved.valuationMethod(), saved.tags());
        return saved;
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) { holdings.delete(id); }

    @PatchMapping("/reorder")
    List<HoldingResponse> reorder(@Valid @RequestBody HoldingReorderRequest request) { return holdings.reorder(request); }
}
