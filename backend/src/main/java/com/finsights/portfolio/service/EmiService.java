package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.EmiPayment;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.RepaymentFrequency;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.EmiPaymentRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Repayment tracking for liability holdings. A repayment is "due" from a few days
 * before its date until it is marked paid; marking it paid records an
 * {@link EmiPayment} and knocks the amount off the loan's outstanding value.
 */
@Service
public class EmiService {

    private static final int LOOKBACK_DAYS = 100;   // how far back an unpaid instalment still shows
    private static final int DUE_SOON_DAYS = 5;     // how early a not-yet-due instalment appears
    private static final int MAX_OCCURRENCES = 12;

    private final HoldingRepository holdings;
    private final EmiPaymentRepository payments;
    private final CurrentUserService currentUser;

    public EmiService(HoldingRepository holdings, EmiPaymentRepository payments, CurrentUserService currentUser) {
        this.holdings = holdings;
        this.payments = payments;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ActionItemResponse> dueItems() {
        String userId = currentUser.currentUser().getId();
        Set<String> paid = payments.findByUser_Id(userId).stream()
                .map(p -> p.getHolding().getId() + "|" + p.getPeriod())
                .collect(Collectors.toSet());
        LocalDate today = LocalDate.now();
        List<ActionItemResponse> items = new ArrayList<>();

        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId)) {
            if (h.getCategory() == null || h.getCategory().getKind() != HoldingKind.LIABILITY) continue;
            RepaymentFrequency freq = h.getRepaymentFrequency();
            if (freq == null) continue;
            BigDecimal amount = instalmentAmount(h);
            String noun = freq == RepaymentFrequency.ONE_TIME ? "Repayment" : "Instalment";

            for (LocalDate due : occurrences(h, today)) {
                if (paid.contains(h.getId() + "|" + due)) continue;
                if (due.isAfter(today.plusDays(DUE_SOON_DAYS))) continue;
                boolean overdue = due.isBefore(today);
                items.add(new ActionItemResponse(
                        overdue ? "EMI_OVERDUE" : "EMI_DUE",
                        overdue ? "WARN" : "INFO",
                        (overdue ? noun + " overdue — " : noun + " due — ") + h.getName(),
                        money(amount, h) + (overdue ? ", was due " + due : ", due " + due),
                        h.getId(), h.getName(), due, amount, due.toString()));
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
        if (payments.findByHolding_IdAndPeriod(holdingId, dueDate).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That repayment is already marked paid");
        }
        BigDecimal amount = instalmentAmount(holding);
        EmiPayment payment = new EmiPayment();
        payment.setUser(user);
        payment.setHolding(holding);
        payment.setPeriod(dueDate);
        payment.setPaidOn(LocalDate.now());
        payment.setAmount(amount);
        payments.save(payment);

        BigDecimal outstanding = holding.getCurrentValue() == null ? BigDecimal.ZERO : holding.getCurrentValue();
        holding.setCurrentValue(outstanding.subtract(amount).max(BigDecimal.ZERO));
        holdings.save(holding);
    }

    /** The scheduled due dates worth showing: the current one plus recent unpaid ones. */
    private List<LocalDate> occurrences(Holding h, LocalDate today) {
        RepaymentFrequency freq = h.getRepaymentFrequency();
        if (freq == RepaymentFrequency.ONE_TIME) {
            return h.getRepaymentDueDate() == null ? List.of() : List.of(h.getRepaymentDueDate());
        }
        LocalDate floor = today.minusDays(LOOKBACK_DAYS);
        List<LocalDate> out = new ArrayList<>();
        LocalDate due = latestOccurrence(freq, h.getEmiDayOfMonth(), today);
        while (!due.isBefore(floor) && out.size() < MAX_OCCURRENCES) {
            out.add(due);
            due = due.minus(freq.step());
        }
        return out;
    }

    /** The most recent scheduled date on or before "today + grace". */
    private LocalDate latestOccurrence(RepaymentFrequency freq, Integer emiDay, LocalDate today) {
        LocalDate grace = today.plusDays(DUE_SOON_DAYS);
        if (freq == RepaymentFrequency.WEEKLY) {
            return grace; // weekly instalments cluster around now; anchor to the grace edge
        }
        int day = emiDay == null ? 1 : Math.min(emiDay, 28);
        LocalDate candidate = today.withDayOfMonth(Math.min(day, today.lengthOfMonth()));
        while (candidate.isAfter(grace)) candidate = candidate.minus(freq.step());
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
