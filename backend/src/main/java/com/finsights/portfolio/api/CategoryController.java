package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.CategoryReorderRequest;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.CategoryResponse;
import com.finsights.portfolio.service.CategoryService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/categories")
public class CategoryController {
    private final CategoryService categories;

    public CategoryController(CategoryService categories) { this.categories = categories; }

    @GetMapping
    List<CategoryResponse> list(@RequestParam(required = false) String currency) { return categories.list(currency); }

    @GetMapping("/{id}")
    CategoryResponse get(@PathVariable String id, @RequestParam(required = false) String currency) {
        return categories.get(id, currency);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    CategoryResponse create(@Valid @RequestBody CategoryRequest request) { return categories.create(request); }

    @PutMapping("/{id}")
    CategoryResponse update(@PathVariable String id, @Valid @RequestBody CategoryRequest request) {
        return categories.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void delete(@PathVariable String id) { categories.delete(id); }

    @PatchMapping("/reorder")
    List<CategoryResponse> reorder(@Valid @RequestBody CategoryReorderRequest request) { return categories.reorder(request); }
}
