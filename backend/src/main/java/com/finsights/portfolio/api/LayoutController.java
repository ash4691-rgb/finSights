package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.LayoutResponse;
import com.finsights.portfolio.dto.LayoutUpdateRequest;
import com.finsights.portfolio.service.LayoutService;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/layouts")
public class LayoutController {
    private final LayoutService layoutService;

    public LayoutController(LayoutService layoutService) { this.layoutService = layoutService; }

    @GetMapping
    Map<String, LayoutResponse> all() { return layoutService.all(); }

    @PutMapping("/{page}")
    LayoutResponse save(@PathVariable String page, @Valid @RequestBody LayoutUpdateRequest req) {
        return layoutService.save(page, req.config());
    }
}
