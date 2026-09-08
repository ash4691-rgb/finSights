package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.dto.DashboardResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class DashboardService {
    private final HoldingService holdingService;

    public DashboardService(HoldingService holdingService) {
        this.holdingService = holdingService;
    }

    public DashboardResponse summary(String currency) {
        List<HoldingResponse> all = holdingService.list(currency);
        List<HoldingResponse> assets = all.stream().filter(h -> h.kind() == HoldingKind.ASSET).toList();
        List<HoldingResponse> liabilities = all.stream().filter(h -> h.kind() == HoldingKind.LIABILITY).toList();
        BigDecimal assetValue = sum(assets, HoldingResponse::currentValue);
        BigDecimal liabilityValue = sum(liabilities, HoldingResponse::currentValue);
        return new DashboardResponse(
                assetValue.subtract(liabilityValue), assetValue, liabilityValue,
                sum(assets, HoldingResponse::investedValue), sum(assets, HoldingResponse::profitLoss),
                breakdown(assets, HoldingResponse::categoryName),
                breakdown(assets, h -> blankAsOther(h.broker(), "Unassigned broker")),
                tagBreakdown(assets));
    }

    private List<DashboardResponse.Breakdown> breakdown(List<HoldingResponse> holdings, Function<HoldingResponse, String> label) {
        Map<String, List<HoldingResponse>> groups = new LinkedHashMap<>();
        holdings.forEach(h -> groups.computeIfAbsent(label.apply(h), ignored -> new ArrayList<>()).add(h));
        return groups.entrySet().stream().map(entry -> new DashboardResponse.Breakdown(
                        entry.getKey(), sum(entry.getValue(), HoldingResponse::currentValue),
                        sum(entry.getValue(), HoldingResponse::investedValue), sum(entry.getValue(), HoldingResponse::profitLoss)))
                .sorted(Comparator.comparing(DashboardResponse.Breakdown::value).reversed()).toList();
    }

    private List<DashboardResponse.Breakdown> tagBreakdown(List<HoldingResponse> holdings) {
        Map<String, List<HoldingResponse>> groups = new LinkedHashMap<>();
        for (HoldingResponse holding : holdings) {
            if (holding.tags().isEmpty()) groups.computeIfAbsent("Untagged", ignored -> new ArrayList<>()).add(holding);
            holding.tags().forEach(tag -> groups.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(holding));
        }
        return groups.entrySet().stream().map(entry -> new DashboardResponse.Breakdown(
                        entry.getKey(), sum(entry.getValue(), HoldingResponse::currentValue),
                        sum(entry.getValue(), HoldingResponse::investedValue), sum(entry.getValue(), HoldingResponse::profitLoss)))
                .sorted(Comparator.comparing(DashboardResponse.Breakdown::value).reversed()).toList();
    }

    private BigDecimal sum(List<HoldingResponse> holdings, Function<HoldingResponse, BigDecimal> getter) {
        return holdings.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String blankAsOther(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}

