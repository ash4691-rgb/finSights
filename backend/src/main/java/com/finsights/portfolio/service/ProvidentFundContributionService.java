package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.TextStyle;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Monthly contributions for provident fund holdings. From the 1st of each month an Action Centre task
 * asks the user to confirm the previous month's contribution (due that month's last day); confirming
 * it logs an ADJUSTMENT transaction and adds the amounts to the balance. Months are confirmed in
 * order, so nothing is skipped or counted twice.
 */
@Service
public class ProvidentFundContributionService {
    static final String KIND = "PROVIDENT_FUND_CONTRIBUTION";
    private static final int MAX_PENDING_MONTHS = 12;

    private final HoldingRepository holdings;
    private final TransactionRepository transactions;
    private final HoldingService holdingService;
    private final CurrentUserService currentUser;

    public ProvidentFundContributionService(HoldingRepository holdings, TransactionRepository transactions,
                                            HoldingService holdingService, CurrentUserService currentUser) {
        this.holdings = holdings;
        this.transactions = transactions;
        this.holdingService = holdingService;
        this.currentUser = currentUser;
    }

    @Transactional(readOnly = true)
    public List<ActionItemResponse> dueItems() {
        return dueItems(LocalDate.now());
    }

    List<ActionItemResponse> dueItems(LocalDate today) {
        List<ActionItemResponse> items = new ArrayList<>();
        for (Holding h : holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(currentUser.currentUser().getId())) {
            if (!isTracked(h)) continue;
            items.addAll(pendingItems(h, today));
        }
        return items;
    }

    @Transactional
    public void confirm(String holdingId, String month) {
        confirm(holdingId, month, LocalDate.now());
    }

    void confirm(String holdingId, String month, LocalDate today) {
        Holding holding = holdings.findByIdAndUser_Id(holdingId, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
        if (!isTracked(holding)) throw bad("This holding has no monthly contribution to confirm");
        YearMonth requested = parse(month);
        YearMonth next = YearMonth.parse(holding.getLastContributionMonth()).plusMonths(1);
        if (!requested.equals(next)) throw bad("Confirm " + label(next) + " first — months are confirmed in order");
        if (!requested.isBefore(YearMonth.from(today))) throw bad("That month isn't over yet");
        book(holding, requested);
    }

    private void book(Holding holding, YearMonth month) {
        BigDecimal employee = orZero(holding.getMonthlyEmployeeContribution());
        BigDecimal employer = orZero(holding.getMonthlyEmployerContribution());
        BigDecimal total = employee.add(employer);
        holdingService.ensureOpeningTransaction(holding); // before invested changes, so the opening position is the pre-contribution one
        Transaction adjustment = new Transaction();
        adjustment.setUser(holding.getUser());
        adjustment.setHolding(holding);
        adjustment.setType(TransactionType.ADJUSTMENT);
        adjustment.setAmount(total);
        adjustment.setDate(month.atEndOfMonth());
        adjustment.setNotes("Provident fund contribution for " + label(month) + " (employee " + employee.toPlainString()
                + ", employer " + employer.toPlainString() + ")");
        transactions.save(adjustment);
        holding.setCurrentValue(orZero(holding.getCurrentValue()).add(total));
        addToSplit(holding, employee, employer);
        holding.setLastContributionMonth(month.toString());
        holdingService.syncFromTransactions(holding);
    }

    /** Keeps the employee/employer totals in step when the holding tracks them separately. */
    private void addToSplit(Holding holding, BigDecimal employee, BigDecimal employer) {
        if (holding.getEmployeeContribution() == null && holding.getEmployerContribution() == null) return;
        holding.setEmployeeContribution(orZero(holding.getEmployeeContribution()).add(employee));
        holding.setEmployerContribution(orZero(holding.getEmployerContribution()).add(employer));
    }

    private List<ActionItemResponse> pendingItems(Holding h, LocalDate today) {
        List<ActionItemResponse> items = new ArrayList<>();
        YearMonth thisMonth = YearMonth.from(today);
        YearMonth month = YearMonth.parse(h.getLastContributionMonth()).plusMonths(1);
        while (month.isBefore(thisMonth) && items.size() < MAX_PENDING_MONTHS) {
            items.add(item(h, month, today));
            month = month.plusMonths(1);
        }
        return items;
    }

    private ActionItemResponse item(Holding h, YearMonth month, LocalDate today) {
        BigDecimal employee = orZero(h.getMonthlyEmployeeContribution());
        BigDecimal employer = orZero(h.getMonthlyEmployerContribution());
        LocalDate due = month.plusMonths(1).atEndOfMonth();
        String severity = due.isBefore(today) ? "WARN" : "INFO";
        String detail = "Confirm " + label(month) + "'s contribution — employee " + employee.toPlainString() + ", employer "
                + employer.toPlainString() + " " + h.getCurrency() + " — to add it to the balance. Due " + due + ".";
        return new ActionItemResponse(KIND, severity, "Confirm provident fund contribution — " + h.getName(), detail,
                h.getId(), h.getName(), h.getBroker(), due, employee.add(employer), month.toString(), null,
                ActionItemResponse.keyOf(KIND, h.getId(), month.toString()));
    }

    /** A holding is tracked once monthly amounts were set (that also stamps its starting month). */
    private boolean isTracked(Holding h) {
        return h.getManualType() == ManualType.PROVIDENT_FUND && h.getLastContributionMonth() != null;
    }

    private YearMonth parse(String month) {
        try {
            return YearMonth.parse(month);
        } catch (DateTimeParseException | NullPointerException e) {
            throw bad("Month must look like 2026-02");
        }
    }

    private String label(YearMonth month) {
        return month.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + month.getYear();
    }

    private BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }
}
