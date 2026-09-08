package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.RepaymentFrequency;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TransactionService {
    private final TransactionRepository transactions;
    private final HoldingRepository holdings;
    private final CurrentUserService currentUser;
    private final FxRateService fx;
    private final HoldingService holdingService;

    public TransactionService(TransactionRepository transactions, HoldingRepository holdings,
                              CurrentUserService currentUser, FxRateService fx, HoldingService holdingService) {
        this.transactions = transactions;
        this.holdings = holdings;
        this.currentUser = currentUser;
        this.fx = fx;
        this.holdingService = holdingService;
    }

    @Transactional(readOnly = true)
    public List<TransactionResponse> list(String holdingId, TransactionType type, String broker,
                                          LocalDate from, LocalDate to, String currency) {
        String userId = currentUser.currentUser().getId();
        return transactions.findByUser_IdOrderByDateDescCreatedAtDesc(userId).stream()
                .filter(t -> holdingId == null || holdingId.isBlank() || t.getHolding().getId().equals(holdingId))
                .filter(t -> type == null || t.getType() == type)
                .filter(t -> broker == null || broker.isBlank()
                        || (t.getHolding().getBroker() != null
                                && t.getHolding().getBroker().toLowerCase().contains(broker.toLowerCase())))
                .filter(t -> from == null || !t.getDate().isBefore(from))
                .filter(t -> to == null || !t.getDate().isAfter(to))
                .map(t -> toResponse(t, currency))
                .toList();
    }

    private static final Set<TransactionType> LIABILITY_TYPES =
            EnumSet.of(TransactionType.BUY, TransactionType.ADJUSTMENT, TransactionType.REPAY);

    @Transactional
    public TransactionResponse create(TransactionRequest request) {
        Holding holding = findOwnedHolding(request.holdingId());
        checkTypeAllowed(request.type(), holding);
        holdingService.ensureOpeningTransaction(holding); // don't let a later BUY silently drop the opening position
        Transaction transaction = new Transaction();
        transaction.setUser(currentUser.currentUser());
        transaction.setHolding(holding);
        copy(request, transaction);
        if (transaction.getType() == TransactionType.REPAY) applyRepay(holding, transaction);
        Transaction saved = transactions.save(transaction);
        holdingService.syncFromTransactions(holding);
        return toResponse(saved, null);
    }

    @Transactional
    public TransactionResponse update(String id, TransactionRequest request) {
        Transaction transaction = findOwned(id);
        Holding previousHolding = transaction.getHolding();
        Holding holding = findOwnedHolding(request.holdingId());
        checkTypeAllowed(request.type(), holding);
        holdingService.ensureOpeningTransaction(holding);
        reverseRepay(transaction);               // undo the old repayment's effect, if any
        transaction.setHolding(holding);
        transaction.setPrincipalPortion(null);
        copy(request, transaction);
        if (transaction.getType() == TransactionType.REPAY) applyRepay(holding, transaction);
        Transaction saved = transactions.save(transaction);
        holdingService.syncFromTransactions(holding);
        if (!previousHolding.getId().equals(holding.getId())) holdingService.syncFromTransactions(previousHolding);
        return toResponse(saved, null);
    }

    @Transactional
    public void delete(String id) {
        Transaction transaction = findOwned(id);
        Holding holding = transaction.getHolding();
        reverseRepay(transaction);
        transactions.delete(transaction);
        holdingService.syncFromTransactions(holding);
    }

    private void checkTypeAllowed(TransactionType type, Holding holding) {
        boolean liability = holding.getCategory() != null && holding.getCategory().getKind() == HoldingKind.LIABILITY;
        if (liability && !LIABILITY_TYPES.contains(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "A liability only takes Repay and Adjustment transactions");
        }
        if (!liability && type == TransactionType.REPAY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Repay applies to liabilities only");
        }
    }

    /** Settles the period's interest first, then cuts principal off the outstanding balance. */
    private void applyRepay(Holding holding, Transaction repay) {
        BigDecimal outstanding = holding.getCurrentValue() == null ? BigDecimal.ZERO : holding.getCurrentValue();
        BigDecimal rate = holding.getFixedAnnualRate() == null ? BigDecimal.ZERO : holding.getFixedAnnualRate();
        BigDecimal periodFraction = periodFraction(holding.getRepaymentFrequency());
        BigDecimal interest = outstanding.multiply(rate, MathContext.DECIMAL64).multiply(periodFraction, MathContext.DECIMAL64);
        BigDecimal principal = repay.getAmount().subtract(interest).max(BigDecimal.ZERO).min(outstanding);
        repay.setPrincipalPortion(principal.setScale(2, RoundingMode.HALF_UP));
        holding.setCurrentValue(outstanding.subtract(principal).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
    }

    private void reverseRepay(Transaction repay) {
        if (repay.getType() != TransactionType.REPAY || repay.getPrincipalPortion() == null) return;
        Holding holding = repay.getHolding();
        BigDecimal outstanding = holding.getCurrentValue() == null ? BigDecimal.ZERO : holding.getCurrentValue();
        holding.setCurrentValue(outstanding.add(repay.getPrincipalPortion()).setScale(2, RoundingMode.HALF_UP));
    }

    private static BigDecimal periodFraction(RepaymentFrequency frequency) {
        if (frequency == null) return BigDecimal.ZERO;
        return switch (frequency) {
            case WEEKLY -> BigDecimal.ONE.divide(BigDecimal.valueOf(52), MathContext.DECIMAL64);
            case MONTHLY -> BigDecimal.ONE.divide(BigDecimal.valueOf(12), MathContext.DECIMAL64);
            case QUARTERLY -> new BigDecimal("0.25");
            case YEARLY, ONE_TIME -> BigDecimal.ONE;
        };
    }

    private void copy(TransactionRequest source, Transaction target) {
        target.setType(source.type());
        target.setDate(source.date());
        target.setAmount(source.amount() == null ? BigDecimal.ZERO : source.amount());
        target.setQuantity(source.quantity());
        target.setInterestPaid(source.type() == TransactionType.INTEREST && Boolean.TRUE.equals(source.interestPaid()));
        target.setNotes(source.notes() == null || source.notes().isBlank() ? null : source.notes().trim());
    }

    private Transaction findOwned(String id) {
        return transactions.findByIdAndUser_Id(id, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transaction not found"));
    }

    private Holding findOwnedHolding(String holdingId) {
        return holdings.findByIdAndUser_Id(holdingId, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Holding not found"));
    }

    private TransactionResponse toResponse(Transaction t, String displayCurrency) {
        Holding h = t.getHolding();
        com.finsights.portfolio.domain.Category category = h.getCategory();
        String currency = h.getCurrency() == null ? "INR" : h.getCurrency();
        BigDecimal amount = t.getAmount() == null ? BigDecimal.ZERO : t.getAmount();
        String outCurrency = currency;
        if (displayCurrency != null && !displayCurrency.isBlank()) {
            amount = fx.convert(amount, currency, displayCurrency);
            outCurrency = displayCurrency.trim().toUpperCase();
        }
        BigDecimal principal = t.getPrincipalPortion();
        if (principal != null && displayCurrency != null && !displayCurrency.isBlank()) {
            principal = fx.convert(principal, currency, displayCurrency);
        }
        return new TransactionResponse(t.getId(), h.getId(), h.getName(), category.getId(), category.getName(),
                h.getBroker(), outCurrency, t.getType(), t.getDate(), amount,
                t.getQuantity(), principal, t.isInterestPaid(), t.getNotes(), t.getCreatedAt());
    }
}
