package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.EmiPayment;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.EmiPaymentRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * EMI schedule tracking for liability holdings. An EMI is "due" from a few days before
 * its date until it is marked paid; marking it paid records an {@link EmiPayment} and
 * knocks the amount off the loan's outstanding value.
 */
@Service
public class EmiService {

    private static final int LOOKBACK_MONTHS = 3;   // how far back an unpaid EMI still shows
    private static final int DUE_SOON_DAYS = 5;     // how early a not-yet-due EMI appears

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
            if (h.getEmiAmount() == null || h.getEmiDayOfMonth() == null) continue;

            for (int offset = LOOKBACK_MONTHS; offset >= 0; offset--) {
                YearMonth month = YearMonth.now().minusMonths(offset);
                LocalDate period = month.atDay(1);
                if (paid.contains(h.getId() + "|" + period)) continue;
                LocalDate dueDate = month.atDay(Math.min(h.getEmiDayOfMonth(), month.lengthOfMonth()));
                if (dueDate.isAfter(today.plusDays(DUE_SOON_DAYS))) continue; // not due yet
                boolean overdue = dueDate.isBefore(today);
                items.add(new ActionItemResponse(
                        overdue ? "EMI_OVERDUE" : "EMI_DUE",
                        overdue ? "WARN" : "INFO",
                        (overdue ? "EMI overdue — " : "EMI due — ") + h.getName(),
                        "₹" + h.getEmiAmount().setScale(0, RoundingMode.HALF_UP).toPlainString()
                                + " for " + month + (overdue ? ", was due " + dueDate : ", due " + dueDate),
                        h.getId(), h.getName(), dueDate, h.getEmiAmount(), period.toString()));
            }
        }
        items.sort((a, b) -> a.dueDate().compareTo(b.dueDate()));
        return items;
    }

    @Transactional
    public void markPaid(String holdingId, YearMonth month) {
        UserAccount user = currentUser.currentUser();
        Holding holding = holdings.findByIdAndUser_Id(holdingId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
        if (holding.getEmiAmount() == null || holding.getEmiDayOfMonth() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This holding has no EMI schedule");
        }
        LocalDate period = month.atDay(1);
        if (payments.findByHolding_IdAndPeriod(holdingId, period).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "That EMI is already marked paid");
        }
        EmiPayment payment = new EmiPayment();
        payment.setUser(user);
        payment.setHolding(holding);
        payment.setPeriod(period);
        payment.setPaidOn(LocalDate.now());
        payment.setAmount(holding.getEmiAmount());
        payments.save(payment);

        BigDecimal outstanding = holding.getCurrentValue() == null ? BigDecimal.ZERO : holding.getCurrentValue();
        holding.setCurrentValue(outstanding.subtract(holding.getEmiAmount()).max(BigDecimal.ZERO));
        holdings.save(holding);
    }
}
