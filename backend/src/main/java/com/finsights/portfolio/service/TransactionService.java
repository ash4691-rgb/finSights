package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.dto.TransactionRequest;
import com.finsights.portfolio.dto.TransactionResponse;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
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

    @Transactional
    public TransactionResponse create(TransactionRequest request) {
        Holding holding = findOwnedHolding(request.holdingId());
        holdingService.ensureOpeningTransaction(holding); // don't let a later BUY silently drop the opening position
        Transaction transaction = new Transaction();
        transaction.setUser(currentUser.currentUser());
        transaction.setHolding(holding);
        copy(request, transaction);
        Transaction saved = transactions.save(transaction);
        holdingService.syncFromTransactions(holding);
        return toResponse(saved, null);
    }

    @Transactional
    public TransactionResponse update(String id, TransactionRequest request) {
        Transaction transaction = findOwned(id);
        Holding previousHolding = transaction.getHolding();
        Holding holding = findOwnedHolding(request.holdingId());
        holdingService.ensureOpeningTransaction(holding);
        transaction.setHolding(holding);
        copy(request, transaction);
        Transaction saved = transactions.save(transaction);
        holdingService.syncFromTransactions(holding);
        if (!previousHolding.getId().equals(holding.getId())) holdingService.syncFromTransactions(previousHolding);
        return toResponse(saved, null);
    }

    @Transactional
    public void delete(String id) {
        Transaction transaction = findOwned(id);
        Holding holding = transaction.getHolding();
        transactions.delete(transaction);
        holdingService.syncFromTransactions(holding);
    }

    private void copy(TransactionRequest source, Transaction target) {
        target.setType(source.type());
        target.setDate(source.date());
        target.setAmount(source.amount() == null ? BigDecimal.ZERO : source.amount());
        target.setQuantity(source.quantity());
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
        return new TransactionResponse(t.getId(), h.getId(), h.getName(), category.getId(), category.getName(),
                h.getBroker(), outCurrency, t.getType(), t.getDate(), amount,
                t.getQuantity(), t.getNotes(), t.getCreatedAt());
    }
}
