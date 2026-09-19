package com.finsights.portfolio.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.finsights.portfolio.domain.DashboardLayout;
import com.finsights.portfolio.dto.LayoutResponse;
import com.finsights.portfolio.repository.DashboardLayoutRepository;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Stores each user's editable-layout arrangement per page as opaque JSON — never inspects widget types. */
@Service
public class LayoutService {
    private static final Set<String> PAGES = Set.of("dashboard", "insights", "brokers");
    private static final int MAX_CONFIG_LENGTH = 65536;

    private final DashboardLayoutRepository layouts;
    private final CurrentUserService currentUser;
    private final ObjectMapper objectMapper;

    public LayoutService(DashboardLayoutRepository layouts, CurrentUserService currentUser, ObjectMapper objectMapper) {
        this.layouts = layouts;
        this.currentUser = currentUser;
        this.objectMapper = objectMapper;
    }

    public Map<String, LayoutResponse> all() {
        return layouts.findByUser_Id(currentUser.currentUser().getId()).stream()
                .map(this::toResponse)
                .collect(Collectors.toMap(LayoutResponse::page, r -> r));
    }

    @Transactional
    public LayoutResponse save(String page, JsonNode config) {
        if (!PAGES.contains(page)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "unknown page");
        }
        String json;
        try {
            json = objectMapper.writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid config");
        }
        if (json.length() > MAX_CONFIG_LENGTH) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "layout too large");
        }
        String userId = currentUser.currentUser().getId();
        DashboardLayout row = layouts.findByUser_IdAndPage(userId, page).orElseGet(DashboardLayout::new);
        if (row.getId() == null) {
            row.setUser(currentUser.currentUser());
            row.setPage(page);
        }
        row.setConfig(json);
        DashboardLayout saved = layouts.save(row);
        return toResponse(saved);
    }

    private LayoutResponse toResponse(DashboardLayout row) {
        try {
            return new LayoutResponse(row.getPage(), objectMapper.readTree(row.getConfig()), row.getUpdatedAt());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt layout config for page " + row.getPage(), e);
        }
    }
}
