package com.finsights.portfolio.service;

import com.finsights.portfolio.dto.BrokersResponse;
import com.finsights.portfolio.dto.BrokersResponse.BrokerGroup;
import com.finsights.portfolio.dto.BrokersResponse.Source;
import com.finsights.portfolio.dto.HoldingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.springframework.stereotype.Service;

@Service
public class BrokerService {

    private static final List<Source> SOURCES = List.of(
            new Source("kite", "Zerodha Kite", "COMING_SOON",
                    "Official Kite Connect API supports holdings, positions, and mutual-fund data. Sync arrives in Phase 3.",
                    List.of("Equity holdings", "Positions", "Mutual funds"),
                    "https://kite.trade/docs/connect/v3/portfolio/"),
            new Source("groww", "Groww", "PLANNED",
                    "Pending confirmation of a supported read API.", List.of("Stocks", "Mutual funds"), null),
            new Source("indmoney", "INDmoney", "PLANNED",
                    "Pending confirmation of a supported read API.", List.of("Stocks", "US stocks", "Net worth"), null),
            new Source("epfo", "EPFO", "PLANNED",
                    "Manual passbook entry for now; no public API.", List.of("Provident fund balance"), null));

    private final HoldingService holdings;

    public BrokerService(HoldingService holdings) {
        this.holdings = holdings;
    }

    public BrokersResponse overview(String currency) {
        Map<String, List<HoldingResponse>> groups = new LinkedHashMap<>();
        for (HoldingResponse h : holdings.list(currency)) {
            if (h.kind() != com.finsights.portfolio.domain.HoldingKind.ASSET) continue;
            String key = h.broker() == null || h.broker().isBlank() ? "Unassigned" : h.broker();
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(h);
        }
        List<BrokerGroup> brokers = new ArrayList<>();
        groups.forEach((name, items) -> {
            TreeSet<String> classes = new TreeSet<>();
            TreeSet<String> currencies = new TreeSet<>();
            Instant lastUpdated = null;
            for (HoldingResponse h : items) {
                classes.add(h.categoryName());
                currencies.add(h.currency());
                if (h.updatedAt() != null && (lastUpdated == null || h.updatedAt().isAfter(lastUpdated))) {
                    lastUpdated = h.updatedAt();
                }
            }
            brokers.add(new BrokerGroup(name, items.size(),
                    sum(items, HoldingResponse::currentValue), sum(items, HoldingResponse::investedValue),
                    sum(items, HoldingResponse::profitLoss), lastUpdated,
                    new ArrayList<>(classes), new ArrayList<>(currencies)));
        });
        brokers.sort(Comparator.comparing(BrokerGroup::currentValue).reversed());
        return new BrokersResponse(brokers, SOURCES);
    }

    private BigDecimal sum(List<HoldingResponse> items, java.util.function.Function<HoldingResponse, BigDecimal> getter) {
        return items.stream().map(getter).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
