package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.ReturnsResponse;
import com.finsights.portfolio.dto.ReturnsResponse.Figure;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * XIRR (money-weighted, annualised) returns per asset holding, per category and for the whole
 * portfolio, built from the transaction ledger plus today's value as a final inflow.
 *
 * <p>Cash flows, investor's view: BUY and ADJUSTMENT are money in (negative), SELL and interest
 * paid out in cash are money out (positive). SPLIT, accrued interest and REPAY move no cash.
 * Liabilities are excluded. Category/portfolio figures convert every flow into the display
 * currency at today's rate, so they ignore historical FX moves.
 */
@Service
public class ReturnsService {
    static final int MIN_DAYS_TO_ANNUALISE = 30;
    static final String NOTE_TOO_RECENT = "Held under " + MIN_DAYS_TO_ANNUALISE + " days, too early to annualise.";
    static final String NOTE_NO_FLOWS = "Not enough transactions to compute a return.";
    static final String NOTE_FX = "Flows in other currencies are converted at today's rate.";

    private final HoldingService holdingService;
    private final TransactionRepository transactions;
    private final CurrentUserService currentUser;
    private final FxRateService fx;
    private final Clock clock;

    @Autowired
    public ReturnsService(HoldingService holdingService, TransactionRepository transactions,
                          CurrentUserService currentUser, FxRateService fx) {
        this(holdingService, transactions, currentUser, fx, Clock.systemDefaultZone());
    }

    ReturnsService(HoldingService holdingService, TransactionRepository transactions,
                   CurrentUserService currentUser, FxRateService fx, Clock clock) {
        this.holdingService = holdingService;
        this.transactions = transactions;
        this.currentUser = currentUser;
        this.fx = fx;
        this.clock = clock;
    }

    public ReturnsResponse returns(String requestedCurrency) {
        UserAccount user = currentUser.currentUser();
        String currency = resolveCurrency(requestedCurrency, user);
        LocalDate today = LocalDate.now(clock);
        // list() also backfills each holding's opening BUY, so read the ledger after it.
        List<HoldingResponse> assets = holdingService.list(currency).stream()
                .filter(h -> h.kind() == HoldingKind.ASSET).toList();
        Map<String, List<Transaction>> ledger = transactions.findByUser_IdOrderByDateDescCreatedAtDesc(user.getId())
                .stream().collect(Collectors.groupingBy(t -> t.getHolding().getId()));

        Map<String, List<CashFlow>> flowsByHolding = new LinkedHashMap<>();
        for (HoldingResponse holding : assets) {
            List<CashFlow> flows = historicalFlows(ledger.getOrDefault(holding.id(), List.of()), holding.defaultCurrency(), currency);
            flowsByHolding.put(holding.id(), flows);
        }

        List<ReturnsResponse.Item> items = assets.stream()
                .map(h -> new ReturnsResponse.Item(h.id(), h.name(), h.categoryName(),
                        figure(flowsByHolding.get(h.id()), h.currentValue(), today, false)))
                .sorted(Comparator.comparing((ReturnsResponse.Item i) -> i.figure().currentValue()).reversed())
                .toList();
        return new ReturnsResponse(currency, today, groupFigure(assets, flowsByHolding, today),
                categoryFigures(assets, flowsByHolding, today), items);
    }

    private List<ReturnsResponse.Group> categoryFigures(List<HoldingResponse> assets, Map<String, List<CashFlow>> flows,
                                                         LocalDate today) {
        Map<String, List<HoldingResponse>> byCategory = new LinkedHashMap<>();
        assets.forEach(h -> byCategory.computeIfAbsent(h.categoryId(), ignored -> new ArrayList<>()).add(h));
        return byCategory.values().stream()
                .map(group -> new ReturnsResponse.Group(group.get(0).categoryId(), group.get(0).categoryName(),
                        groupFigure(group, flows, today)))
                .sorted(Comparator.comparing((ReturnsResponse.Group g) -> g.figure().currentValue()).reversed())
                .toList();
    }

    private Figure groupFigure(List<HoldingResponse> holdings, Map<String, List<CashFlow>> flows, LocalDate today) {
        List<CashFlow> combined = new ArrayList<>();
        BigDecimal current = BigDecimal.ZERO;
        for (HoldingResponse holding : holdings) {
            combined.addAll(flows.get(holding.id()));
            current = current.add(zeroIfNull(holding.currentValue()));
        }
        return figure(combined, current, today, mixesCurrencies(holdings));
    }

    Figure figure(List<CashFlow> historical, BigDecimal currentValue, LocalDate today, boolean converted) {
        BigDecimal current = zeroIfNull(currentValue).setScale(2, RoundingMode.HALF_UP);
        BigDecimal netInvested = money(-historical.stream().mapToDouble(CashFlow::amount).sum());
        BigDecimal gain = current.subtract(netInvested);
        LocalDate since = historical.stream().map(CashFlow::date).min(Comparator.naturalOrder()).orElse(null);
        String fxNote = converted ? NOTE_FX : null;

        if (since == null) return new Figure(null, netInvested, current, gain, null, NOTE_NO_FLOWS);
        if (ChronoUnit.DAYS.between(since, today) < MIN_DAYS_TO_ANNUALISE) {
            return new Figure(null, netInvested, current, gain, since, NOTE_TOO_RECENT);
        }
        List<CashFlow> all = new ArrayList<>(historical);
        all.add(new CashFlow(today, current.doubleValue()));
        OptionalDouble rate = XirrCalculator.xirr(all);
        if (rate.isEmpty()) return new Figure(null, netInvested, current, gain, since, NOTE_NO_FLOWS);
        BigDecimal percent = BigDecimal.valueOf(rate.getAsDouble() * 100).setScale(2, RoundingMode.HALF_UP);
        return new Figure(percent, netInvested, current, gain, since, fxNote);
    }

    List<CashFlow> historicalFlows(List<Transaction> ledger, String holdingCurrency, String currency) {
        List<CashFlow> flows = new ArrayList<>();
        for (Transaction transaction : ledger) {
            double signed = signedAmount(transaction);
            if (signed == 0) continue;
            BigDecimal converted = fx.convert(BigDecimal.valueOf(signed), holdingCurrency, currency);
            flows.add(new CashFlow(transaction.getDate(), converted.doubleValue()));
        }
        return flows;
    }

    static double signedAmount(Transaction transaction) {
        if (transaction.getAmount() == null || transaction.getType() == null) return 0;
        double amount = transaction.getAmount().doubleValue();
        return switch (transaction.getType()) {
            case BUY, ADJUSTMENT -> -amount;
            case SELL -> amount;
            case INTEREST -> transaction.isInterestPaid() ? amount : 0;
            case SPLIT, REPAY -> 0;
        };
    }

    private static boolean mixesCurrencies(List<HoldingResponse> holdings) {
        return holdings.stream().map(HoldingResponse::defaultCurrency).distinct().count() > 1;
    }

    private static String resolveCurrency(String requested, UserAccount user) {
        if (requested != null && !requested.isBlank()) return requested.trim().toUpperCase(Locale.ROOT);
        String base = user.getBaseCurrency();
        if (base == null || base.isBlank()) return "INR";
        return base;
    }

    private static BigDecimal money(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
