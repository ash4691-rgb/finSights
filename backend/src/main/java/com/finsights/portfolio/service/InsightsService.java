package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.DashboardResponse.Breakdown;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.InsightsResponse;
import com.finsights.portfolio.dto.InsightsResponse.Mover;
import com.finsights.portfolio.dto.InsightsResponse.Warning;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Service;

@Service
public class InsightsService {
    private final HoldingService holdings;
    private final CurrentUserService currentUser;

    public InsightsService(HoldingService holdings, CurrentUserService currentUser) {
        this.holdings = holdings;
        this.currentUser = currentUser;
    }

    public InsightsResponse insights(String currency) {
        boolean converted = currency != null && !currency.isBlank();
        // "By currency" always groups by each holding's ORIGINAL currency, even once the
        // amounts themselves have been converted for display.
        Map<String, String> originalCurrencyById = holdings.list().stream()
                .collect(java.util.stream.Collectors.toMap(HoldingResponse::id, HoldingResponse::currency));
        List<HoldingResponse> all = converted ? holdings.list(currency) : holdings.list();
        List<HoldingResponse> assets = all.stream().filter(h -> h.kind() == HoldingKind.ASSET).toList();
        String baseCurrency = currentUser.currentUser().getBaseCurrency();

        List<Mover> gainers = assets.stream()
                .filter(h -> h.profitLoss().signum() > 0)
                .sorted(Comparator.comparing(HoldingResponse::profitLoss).reversed())
                .limit(5)
                .map(h -> new Mover(h.id(), h.name(), h.profitLoss(), h.profitLossPercentage()))
                .toList();
        List<Mover> losers = assets.stream()
                .filter(h -> h.profitLoss().signum() < 0)
                .sorted(Comparator.comparing(HoldingResponse::profitLoss))
                .limit(5)
                .map(h -> new Mover(h.id(), h.name(), h.profitLoss(), h.profitLossPercentage()))
                .toList();

        return new InsightsResponse(
                breakdown(assets, HoldingResponse::categoryName),
                breakdown(assets, h -> blank(h.broker(), "Unassigned broker")),
                tagBreakdown(assets),
                breakdown(assets, h -> originalCurrencyById.getOrDefault(h.id(), h.currency())),
                breakdown(assets, this::liquidityBucket),
                gainers, losers,
                warnings(all, baseCurrency, converted));
    }

    private String liquidityBucket(HoldingResponse h) {
        if (Boolean.TRUE.equals(h.blocked())) return "Blocked";
        return Boolean.TRUE.equals(h.liquidWithinSevenDays()) ? "Liquid within 7 days" : "Not immediately liquid";
    }

    private List<Warning> warnings(List<HoldingResponse> holdings, String baseCurrency, boolean converted) {
        List<Warning> warnings = new ArrayList<>();
        for (HoldingResponse h : holdings) {
            if (h.kind() == HoldingKind.ASSET && h.currentValue().signum() == 0) {
                warnings.add(new Warning("WARN", "Has no current value recorded.", h.id(), h.name()));
            }
            if (h.broker() == null || h.broker().isBlank()) {
                warnings.add(new Warning("INFO", "Not assigned to a broker or owner.", h.id(), h.name()));
            }
            if (!converted && h.currency() != null && !h.currency().equalsIgnoreCase(baseCurrency)) {
                warnings.add(new Warning("INFO",
                        "Held in " + h.currency() + "; shown without conversion to " + baseCurrency
                                + ". Pick a display currency above to convert it.",
                        h.id(), h.name()));
            }
            if (h.kind() == HoldingKind.ASSET && h.investedValue().signum() == 0 && h.currentValue().signum() > 0
                    && h.valuationMethod() != ValuationMethod.MANUAL) {
                warnings.add(new Warning("INFO", "No invested amount recorded, so P&L may be misleading.",
                        h.id(), h.name()));
            }
        }
        warnings.sort(Comparator.comparing((Warning w) -> w.severity().equals("WARN") ? 0 : 1));
        return warnings;
    }

    private List<Breakdown> breakdown(List<HoldingResponse> holdings, Function<HoldingResponse, String> label) {
        Map<String, List<HoldingResponse>> groups = new LinkedHashMap<>();
        holdings.forEach(h -> groups.computeIfAbsent(label.apply(h), k -> new ArrayList<>()).add(h));
        return toBreakdowns(groups);
    }

    private List<Breakdown> tagBreakdown(List<HoldingResponse> holdings) {
        Map<String, List<HoldingResponse>> groups = new LinkedHashMap<>();
        for (HoldingResponse h : holdings) {
            if (h.tags().isEmpty()) groups.computeIfAbsent("Untagged", k -> new ArrayList<>()).add(h);
            h.tags().forEach(tag -> groups.computeIfAbsent(tag, k -> new ArrayList<>()).add(h));
        }
        return toBreakdowns(groups);
    }

    private List<Breakdown> toBreakdowns(Map<String, List<HoldingResponse>> groups) {
        return groups.entrySet().stream()
                .map(e -> new Breakdown(e.getKey(), sum(e.getValue(), HoldingResponse::currentValue),
                        sum(e.getValue(), HoldingResponse::investedValue), sum(e.getValue(), HoldingResponse::profitLoss)))
                .sorted(Comparator.comparing(Breakdown::value).reversed())
                .toList();
    }

    private BigDecimal sum(List<HoldingResponse> holdings, Function<HoldingResponse, BigDecimal> getter) {
        return holdings.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private String blank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
