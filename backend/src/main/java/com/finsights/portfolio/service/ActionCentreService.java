package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * The Action Centre: everything the user should look at or confirm — EMIs due,
 * interest payouts to log, fixed-rate deposits/loans that have matured, live
 * prices that could not be fetched, and the data-quality checks. Rows the user
 * has marked done / deferred / deleted are filtered out here.
 */
@Service
public class ActionCentreService {

    private final EmiService emis;
    private final InterestPayoutService interestPayouts;
    private final ActionDismissalService dismissals;
    private final CurrentUserService currentUser;

    public ActionCentreService(EmiService emis, InterestPayoutService interestPayouts,
                               ActionDismissalService dismissals, CurrentUserService currentUser) {
        this.emis = emis;
        this.interestPayouts = interestPayouts;
        this.dismissals = dismissals;
        this.currentUser = currentUser;
    }

    public List<ActionItemResponse> actions(List<HoldingResponse> holdings, boolean converted) {
        String baseCurrency = currentUser.currentUser().getBaseCurrency();
        LocalDate today = LocalDate.now();
        List<ActionItemResponse> items = new ArrayList<>(emis.dueItems());
        items.addAll(interestPayouts.dueItems());

        for (HoldingResponse h : holdings) {
            if (h.valuationMethod() == ValuationMethod.FIXED_RATE && h.fixedRateEndDate() != null
                    && !h.fixedRateEndDate().isAfter(today)) {
                items.add(item("FIXED_RATE_MATURED", "WARN",
                        "Matured — " + h.name(),
                        "Reached its maturity date on " + h.fixedRateEndDate()
                                + ". Confirm the payout and record what happened to the proceeds.",
                        h));
            }
            if (h.valuationMethod() == ValuationMethod.MARKET_PRICE
                    && h.tickerSymbol() != null && !h.tickerSymbol().isBlank() && h.priceUpdatedAt() == null) {
                items.add(item("PRICE_UNAVAILABLE", "WARN",
                        "No live price — " + h.name(),
                        "Couldn't fetch a price for " + h.tickerSymbol()
                                + ". Check the ticker symbol or set the value manually.",
                        h));
            }
            if (h.kind() == HoldingKind.ASSET && h.currentValue().signum() == 0) {
                items.add(item("DQ_NO_VALUE", "WARN", "No current value — " + h.name(),
                        "This asset has no current value recorded.", h));
            }
            if (h.broker() == null || h.broker().isBlank()) {
                items.add(item("DQ_NO_BROKER", "INFO", "No broker — " + h.name(),
                        "Not assigned to a broker.", h));
            }
            if (!converted && h.currency() != null && !h.currency().equalsIgnoreCase(baseCurrency)) {
                items.add(item("DQ_FOREIGN_CURRENCY", "INFO", "Foreign currency — " + h.name(),
                        "Held in " + h.currency() + "; shown without conversion to " + baseCurrency
                                + ". Pick a display currency above to convert it.", h));
            }
            if (h.kind() == HoldingKind.ASSET && h.investedValue().signum() == 0 && h.currentValue().signum() > 0
                    && h.valuationMethod() != ValuationMethod.MANUAL) {
                items.add(item("DQ_NO_INVESTED", "INFO", "No invested amount — " + h.name(),
                        "No invested amount recorded, so P&L may be misleading.", h));
            }
        }

        Set<String> suppressed = dismissals.suppressedKeys();
        return items.stream()
                .filter(a -> !suppressed.contains(a.key()))
                .sorted(Comparator
                        .comparingInt((ActionItemResponse a) -> a.severity().equals("WARN") ? 0 : 1)
                        .thenComparing(a -> a.dueDate() == null ? LocalDate.MAX : a.dueDate()))
                .toList();
    }

    private static ActionItemResponse item(String kind, String severity, String title, String detail, HoldingResponse h) {
        return new ActionItemResponse(kind, severity, title, detail, h.id(), h.name(), null, null, null,
                ActionItemResponse.keyOf(kind, h.id(), null));
    }
}
