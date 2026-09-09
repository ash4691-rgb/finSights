package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.EmiPayment;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.RepaymentFrequency;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.EmiPaymentRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Repayment tracking for liability holdings. An instalment is "due" from a few days
 * before its date until it is settled; marking it paid records an {@link EmiPayment},
 * logs a REPAY transaction for the principal portion, and knocks that off the loan's
 * outstanding value.
 *
 * <p>The schedule is the grid {@code firstInstalment, +step, +2·step, …}. What's owed
 * is every grid date not yet settled (by an {@link EmiPayment} or a REPAY transaction),
 * up to a few days ahead — see {@link DueSchedule}.
 */
@Service
public class EmiService {

    private static final int DUE_SOON_DAYS = 5;     // how early a not-yet-due instalment appears
    private static final int MAX_OCCURRENCES = 6;   // cap the backlog shown for one loan

    private final HoldingRepository holdings;
    private final EmiPaymentRepository payments;
    private final TransactionRepository transactions;
    private final CurrentUserService currentUser;

    public EmiService(HoldingRepository holdings, EmiPaymentRepository payments,
                      TransactionRepository transactions, CurrentUserService currentUser) {
        this.holdings = holdings;
        this.payments = payments;
        this.transactions = transactions;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ActionItemResponse> dueItems() {
        String userId = currentUser.currentUser().getId();
        LocalDate today = LocalDate.now();
        List<ActionItemResponse> items = new ArrayList<>();

        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)) {
            if (h.getCategory() == null || h.getCategory().getKind() != HoldingKind.LIABILITY) continue;
            RepaymentFrequency freq = h.getRepaymentFrequency();
            if (freq == null) continue;
            BigDecimal amount = instalmentAmount(h);
            String noun = freq == RepaymentFrequency.ONE_TIME ? "Repayment" : "Instalment";

            for (LocalDate due : occurrences(h, today)) {
                if (due.isAfter(today.plusDays(DUE_SOON_DAYS))) continue;
                boolean overdue = due.isBefore(today);
                String kind = overdue ? "EMI_OVERDUE" : "EMI_DUE";
                items.add(new ActionItemResponse(
                        kind,
                        overdue ? "WARN" : "INFO",
                        (overdue ? noun + " overdue — " : noun + " due — ") + h.getName(),
                        money(amount, h) + (overdue ? ", was due " + due : ", due " + due),
                        h.getId(), h.getName(), due, amount, due.toString(),
                        ActionItemResponse.keyOf("EMI", h.getId(), due.toString())));
            }
        }
        items.sort((a, b) -> a.dueDate().compareTo(b.dueDate()));
        return items;
    }

    @Transactional
    public void markPaid(String holdingId, LocalDate dueDate) {
        UserAccount user = currentUser.currentUser();
        Holding holding = holdings.findByIdAndUser_Id(holdingId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
        if (holding.getRepaymentFrequency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This holding has no repayment schedule");
        }
        if (!occurrences(holding, LocalDate.now()).contains(dueDate)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "That instalment is already settled, or is not due yet");
        }
        BigDecimal instalment = instalmentAmount(holding);
        BigDecimal outstanding = holding.getCurrentValue() == null ? BigDecimal.ZERO : holding.getCurrentValue();
        BigDecimal rate = holding.getFixedAnnualRate() == null ? BigDecimal.ZERO : holding.getFixedAnnualRate();
        BigDecimal interest = outstanding
                .multiply(rate, MathContext.DECIMAL64)
                .multiply(TransactionService.periodFraction(holding.getRepaymentFrequency()), MathContext.DECIMAL64)
                .max(BigDecimal.ZERO)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal principal = instalment.subtract(interest).max(BigDecimal.ZERO).min(outstanding)
                .setScale(2, RoundingMode.HALF_UP);

        String cur = "INR".equalsIgnoreCase(holding.getCurrency()) ? "₹" : holding.getCurrency() + " ";
        String notes = "Auto-logged from Action centre. Instalment " + cur + plain(instalment)
                + " less " + cur + plain(interest) + " interest on outstanding " + cur + plain(outstanding)
                + " leaves " + cur + plain(principal) + " toward principal.";

        // The REPAY carries the principal paydown only; interest is spent, not owed.
        Transaction repay = new Transaction();
        repay.setUser(user);
        repay.setHolding(holding);
        repay.setType(TransactionType.REPAY);
        repay.setDate(dueDate);
        repay.setAmount(principal);
        repay.setPrincipalPortion(principal);
        repay.setNotes(notes);
        transactions.save(repay);

        EmiPayment payment = new EmiPayment();
        payment.setUser(user);
        payment.setHolding(holding);
        payment.setPeriod(dueDate);
        payment.setPaidOn(LocalDate.now());
        payment.setAmount(instalment);
        payments.save(payment);

        holding.setCurrentValue(outstanding.subtract(principal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        holdings.save(holding);
    }

    private static String plain(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** Instalment dates still owed, earliest first, anchored past the last one settled. */
    private List<LocalDate> occurrences(Holding h, LocalDate today) {
        RepaymentFrequency freq = h.getRepaymentFrequency();
        LocalDate horizon = today.plusDays(DUE_SOON_DAYS);

        if (freq == RepaymentFrequency.ONE_TIME) {
            LocalDate due = h.getRepaymentDueDate();
            if (due == null || settledOneTime(h, due)) return List.of();
            return List.of(due);
        }

        Period step = freq.step();
        LocalDate firstDue = firstInstalment(h, freq);
        return DueSchedule.unsettled(firstDue, step, settledPeriods(h, firstDue, step),
                horizon, null, MAX_OCCURRENCES);
    }

    /** Grid dates already covered by a recorded payment or a REPAY transaction. */
    private Set<LocalDate> settledPeriods(Holding h, LocalDate firstDue, Period step) {
        Set<LocalDate> settled = new HashSet<>();
        for (EmiPayment p : payments.findByHolding_Id(h.getId())) settled.add(p.getPeriod());
        for (Transaction t : transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(h.getId())) {
            if (t.getType() != TransactionType.REPAY) continue;
            LocalDate grid = DueSchedule.snapToPeriod(firstDue, step, t.getDate());
            if (grid != null) settled.add(grid);
        }
        return settled;
    }

    private boolean settledOneTime(Holding h, LocalDate due) {
        if (payments.findByHolding_IdAndPeriod(h.getId(), due).isPresent()) return true;
        return h.getCurrentValue() != null && h.getCurrentValue().signum() <= 0;
    }

    /** First scheduled instalment: the {@code emiDayOfMonth} on/after the day the loan was added (a week after, weekly). */
    private LocalDate firstInstalment(Holding h, RepaymentFrequency freq) {
        LocalDate created = h.getCreatedAt() == null ? LocalDate.now()
                : h.getCreatedAt().atZone(ZoneId.systemDefault()).toLocalDate();
        if (freq == RepaymentFrequency.WEEKLY) return created.plusWeeks(1);
        int day = h.getEmiDayOfMonth() == null ? 1 : Math.min(Math.max(h.getEmiDayOfMonth(), 1), 28);
        LocalDate candidate = created.withDayOfMonth(day);
        if (!candidate.isAfter(created)) candidate = candidate.plus(freq.step());
        return candidate;
    }

    /** Instalment amount: the stated EMI, or total ÷ remaining instalments, or the full outstanding for a bullet loan. */
    private BigDecimal instalmentAmount(Holding h) {
        if (h.getRepaymentFrequency() == RepaymentFrequency.ONE_TIME) {
            return h.getCurrentValue() == null ? BigDecimal.ZERO : h.getCurrentValue();
        }
        if (h.getEmiAmount() != null) return h.getEmiAmount();
        if (h.getLoanTermMonths() != null && h.getLoanTermMonths() > 0 && h.getInvestedValue() != null) {
            return h.getInvestedValue().divide(BigDecimal.valueOf(h.getLoanTermMonths()), 2, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    private static String money(BigDecimal amount, Holding h) {
        String prefix = "INR".equalsIgnoreCase(h.getCurrency()) ? "₹" : h.getCurrency() + " ";
        return prefix + amount.setScale(0, RoundingMode.HALF_UP).toPlainString();
    }
}
