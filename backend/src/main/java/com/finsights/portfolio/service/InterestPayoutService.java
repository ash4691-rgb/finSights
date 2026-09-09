package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.CompoundingFrequency;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Interest-payout reminders for fixed-rate assets that pay out periodically
 * (monthly / quarterly / half-yearly / yearly — not "at maturity", and not the
 * daily/weekly accrual buckets). Confirming one logs a cash INTEREST transaction
 * so the amount lands in realised P/L.
 *
 * <p>Due dates are the grid {@code start + step, start + 2·step, …}. The next one
 * needing attention is the earliest grid date after the latest INTEREST already
 * on the ledger (see {@link DueSchedule}).
 */
@Service
public class InterestPayoutService {

    private static final int DUE_SOON_DAYS = 5;     // how early a not-yet-due payout appears
    private static final int MAX_OCCURRENCES = 4;   // cap the backlog shown for one holding

    private final HoldingRepository holdings;
    private final TransactionRepository transactions;
    private final TransactionService transactionService;
    private final CurrentUserService currentUser;

    public InterestPayoutService(HoldingRepository holdings, TransactionRepository transactions,
                                 TransactionService transactionService, CurrentUserService currentUser) {
        this.holdings = holdings;
        this.transactions = transactions;
        this.transactionService = transactionService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ActionItemResponse> dueItems() {
        String userId = currentUser.currentUser().getId();
        LocalDate today = LocalDate.now();
        List<ActionItemResponse> items = new ArrayList<>();

        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)) {
            if (!paysPeriodicInterest(h)) continue;
            BigDecimal payout = payoutAmount(h);
            if (payout.signum() <= 0) continue;

            for (LocalDate due : dueDates(h, today)) {
                boolean overdue = due.isBefore(today);
                items.add(new ActionItemResponse(
                        overdue ? "INTEREST_OVERDUE" : "INTEREST_DUE",
                        overdue ? "WARN" : "INFO",
                        (overdue ? "Interest payout overdue — " : "Interest payout due — ") + h.getName(),
                        money(payout, h) + (overdue ? ", was due " + due : ", due " + due)
                                + ". Confirm to book it as realised income.",
                        h.getId(), h.getName(), due, payout, due.toString()));
            }
        }
        items.sort((a, b) -> a.dueDate().compareTo(b.dueDate()));
        return items;
    }

    @Transactional
    public void confirm(String holdingId, LocalDate dueDate) {
        UserAccount user = currentUser.currentUser();
        Holding holding = holdings.findByIdAndUser_Id(holdingId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
        if (!paysPeriodicInterest(holding)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This holding has no interest-payout schedule");
        }
        if (!dueDates(holding, LocalDate.now()).contains(dueDate)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That payout is already recorded, or is not due yet");
        }
        BigDecimal payout = payoutAmount(holding);
        String notes = "Auto-logged from Action centre. " + periodLabel(holding.getCompoundingFrequency())
                + " interest at " + ratePercent(holding) + "% p.a. on "
                + money(holding.getInvestedValue(), holding) + " principal.";
        transactionService.create(new TransactionRequest(
                holdingId, TransactionType.INTEREST, dueDate, payout, null, true, notes));
    }

    /** Grid payout dates still owed, earliest first, anchored past the latest INTEREST on the ledger. */
    private List<LocalDate> dueDates(Holding h, LocalDate today) {
        Period step = stepFor(h.getCompoundingFrequency());
        LocalDate firstDue = h.getFixedRateStartDate().plus(step);
        List<Transaction> ledger = transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(h.getId());
        Set<LocalDate> settled = ledger.stream()
                .filter(t -> t.getType() == TransactionType.INTEREST)
                .map(t -> DueSchedule.snapToPeriod(firstDue, step, t.getDate()))
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(HashSet::new));
        return DueSchedule.unsettled(firstDue, step, settled,
                today.plusDays(DUE_SOON_DAYS), h.getFixedRateEndDate(), MAX_OCCURRENCES);
    }

    private boolean paysPeriodicInterest(Holding h) {
        return h.getCategory() != null && h.getCategory().getKind() == HoldingKind.ASSET
                && h.getValuationMethod() == ValuationMethod.FIXED_RATE
                && stepFor(h.getCompoundingFrequency()) != null
                && h.getFixedRateStartDate() != null
                && h.getFixedAnnualRate() != null && h.getFixedAnnualRate().signum() > 0
                && h.getInvestedValue() != null && h.getInvestedValue().signum() > 0;
    }

    /** Principal x annual rate / payouts-per-year. */
    private BigDecimal payoutAmount(Holding h) {
        int perYear = h.getCompoundingFrequency().periodsPerYear();
        return h.getInvestedValue()
                .multiply(h.getFixedAnnualRate(), MathContext.DECIMAL64)
                .divide(BigDecimal.valueOf(perYear), 2, RoundingMode.HALF_UP);
    }

    private static Period stepFor(CompoundingFrequency freq) {
        if (freq == null) return null;
        return switch (freq) {
            case MONTHLY -> Period.ofMonths(1);
            case QUARTERLY -> Period.ofMonths(3);
            case HALF_YEARLY -> Period.ofMonths(6);
            case ANNUALLY -> Period.ofYears(1);
            case DAILY, WEEKLY, AT_MATURITY -> null;   // accrual buckets / single settlement — no payout reminder
        };
    }

    private static String periodLabel(CompoundingFrequency freq) {
        return switch (freq) {
            case MONTHLY -> "Monthly";
            case QUARTERLY -> "Quarterly";
            case HALF_YEARLY -> "Half-yearly";
            case ANNUALLY -> "Yearly";
            default -> "Periodic";
        };
    }

    private static String ratePercent(Holding h) {
        return h.getFixedAnnualRate()
                .multiply(BigDecimal.valueOf(100))
                .stripTrailingZeros().toPlainString();
    }

    private static String money(BigDecimal amount, Holding h) {
        String prefix = "INR".equalsIgnoreCase(h.getCurrency()) ? "₹" : h.getCurrency() + " ";
        return prefix + amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
