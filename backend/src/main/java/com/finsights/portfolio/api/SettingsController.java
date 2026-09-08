package com.finsights.portfolio.api;

import com.finsights.portfolio.dto.SettingsRequest;
import com.finsights.portfolio.dto.SettingsResponse;
import com.finsights.portfolio.service.SettingsService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class SettingsController {
    private final SettingsService settings;

    public SettingsController(SettingsService settings) { this.settings = settings; }

    @GetMapping("/settings")
    SettingsResponse current() { return settings.current(); }

    @PutMapping("/settings")
    SettingsResponse update(@Valid @RequestBody SettingsRequest request) { return settings.update(request); }

    @GetMapping(value = "/account/export", produces = "application/json")
    ResponseEntity<Map<String, Object>> export() {
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=finsights-export.json")
                .body(settings.export());
    }

    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void deleteAccount(HttpServletRequest request) {
        settings.deleteAccount();
        var session = request.getSession(false);
        if (session != null) session.invalidate();
    }
}
