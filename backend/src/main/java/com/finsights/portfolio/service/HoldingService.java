package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.RepaymentFrequency;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.MarketQuoteResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class HoldingService {
    private final HoldingRepository holdings;
    private final CategoryRepository categories;
    private final CurrentUserService currentUser;
    private final ValuationService valuations;
    private final FxRateService fx;
    private final TransactionRepository transactions;
    private final PriceSnapshotService snapshots;
    private final MarketDataService marketData;
    private final com.finsights.portfolio.repository.EmiPaymentRepository emiPayments;

    /** MARKET_PRICE holdings are re-priced from the live feed no more often than this. */
    private static final Duration PRICE_MAX_AGE = Duration.ofMinutes(15);

    public HoldingService(HoldingRepository holdings, CategoryRepository categories, CurrentUserService currentUser,
                          ValuationService valuations, FxRateService fx, TransactionRepository transactions,
                          PriceSnapshotService snapshots, MarketDataService marketData,
                          com.finsights.portfolio.repository.EmiPaymentRepository emiPayments) {
        this.holdings = holdings;
        this.emiPayments = emiPayments;
        this.categories = categories;
        this.currentUser = currentUser;
        this.valuations = valuations;
        this.fx = fx;
        this.transactions = transactions;
        this.snapshots = snapshots;
        this.marketData = marketData;
    }

    @Transactional
    public List<HoldingResponse> list() {
        String userId = currentUser.currentUser().getId();
        List<Holding> owned = holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId);
        backfillHoldingRefs(owned);
        backfillOpeningTransactions(owned, userId);
        refreshMarketPrices(owned);
        return owned.stream().map(this::toResponse).toList();
    }

    /**
     * Re-prices MARKET_PRICE holdings that have a ticker, a quantity, and a price older
     * than {@link #PRICE_MAX_AGE}. Runs on every Holdings-page load; the feed itself is
     * cached and every failure is swallowed so a dead feed never blocks the page.
     */
    private void refreshMarketPrices(List<Holding> owned) {
        Instant cutoff = Instant.now().minus(PRICE_MAX_AGE);
        List<Holding> stale = owned.stream()
                .filter(h -> h.getValuationMethod() == ValuationMethod.MARKET_PRICE)
                .filter(h -> h.getTickerSymbol() != null && !h.getTickerSymbol().isBlank())
                .filter(h -> h.getQuantity() != null && h.getQuantity().signum() > 0)
                .filter(h -> h.getPriceUpdatedAt() == null || h.getPriceUpdatedAt().isBefore(cutoff))
                .toList();
        if (stale.isEmpty()) return;

        Map<String, MarketQuoteResponse> quotes = marketData.quotes(stale.stream()
                .map(h -> h.getTickerSymbol().trim().toUpperCase())
                .collect(Collectors.toSet()));
        Instant now = Instant.now();
        for (Holding holding : stale) {
            MarketQuoteResponse quote = quotes.get(holding.getTickerSymbol().trim().toUpperCase());
            if (quote == null || quote.price() == null || quote.price().signum() <= 0) continue;
            BigDecimal unitPrice = convertToHoldingCurrency(quote.price(), quote.currency(), holding.getCurrency());
            BigDecimal newValue = unitPrice.multiply(holding.getQuantity()).setScale(2, RoundingMode.HALF_UP);
            BigDecimal oldValue = zeroIfNull(holding.getCurrentValue());
            holding.setCurrentValue(newValue);
            holding.setPriceUpdatedAt(now);
            holdings.save(holding);
            if (oldValue.compareTo(newValue) != 0) {
                snapshots.record(SnapshotSubject.HOLDING, holding.getId(), holding.getUser(), newValue);
            }
        }
    }

    private BigDecimal convertToHoldingCurrency(BigDecimal amount, String from, String to) {
        try {
            return fx.convert(amount, from, to);
        } catch (RuntimeException e) {
            return amount; // unsupported currency pair — treat the quote as already in the holding's currency
        }
    }

    /** Gives any pre-existing holding created before the reference feature a stable ref, once. */
    private void backfillHoldingRefs(List<Holding> owned) {
        List<Holding> missing = owned.stream().filter(h -> h.getHoldingRef() == null || h.getHoldingRef().isBlank()).toList();
        if (missing.isEmpty()) return;
        missing.forEach(h -> h.setHoldingRef(generateHoldingRef(h.getName())));
        holdings.saveAll(missing);
    }

    /** Every holding with money or units in it has an opening BUY; pre-existing ones are given one, once. */
    private void backfillOpeningTransactions(List<Holding> owned, String userId) {
        List<Holding> candidates = owned.stream()
                .filter(h -> zeroIfNull(h.getInvestedValue()).signum() > 0 || h.getQuantity() != null)
                .toList();
        if (candidates.isEmpty()) return;
        Set<String> withTransactions = transactions.findHoldingIdsWithTransactions(userId);
        List<Transaction> openings = candidates.stream()
                .filter(h -> !withTransactions.contains(h.getId()))
                .map(h -> buildOpeningTransaction(h, zeroIfNull(h.getInvestedValue()), h.getQuantity()))
                .toList();
        if (!openings.isEmpty()) transactions.saveAll(openings);
    }

    /** Same holdings, with invested/current/P&amp;L converted into {@code displayCurrency} for viewing. */
    public List<HoldingResponse> list(String displayCurrency) {
        List<HoldingResponse> native_ = list();
        if (displayCurrency == null || displayCurrency.isBlank()) return native_;
        String target = displayCurrency.trim().toUpperCase();
        return native_.stream().map(h -> fx.convert(h, target)).toList();
    }

    public List<HoldingResponse> listByCategory(String categoryId, String displayCurrency) {
        return list(displayCurrency).stream().filter(h -> h.categoryId().equals(categoryId)).toList();
    }

    @Transactional
    public HoldingResponse get(String id) {
        Holding holding = findOwned(id);
        if (holding.getHoldingRef() == null || holding.getHoldingRef().isBlank()) {
            holding.setHoldingRef(generateHoldingRef(holding.getName()));
            holdings.save(holding);
        }
        return toResponse(holding);
    }

    public com.finsights.portfolio.dto.ValuationDetailResponse valuationDetail(String id) {
        return valuations.explain(findOwned(id));
    }

    @Transactional
    public HoldingResponse create(HoldingRequest request) {
        String userId = currentUser.currentUser().getId();
        requireUniqueNameAndBroker(userId, request.name().trim(), request.broker().trim(), null);
        Holding holding = new Holding();
        holding.setUser(currentUser.currentUser());
        holding.setSortOrder((int) holdings.countByUser_Id(userId));
        copy(request, holding);
        holding.setHoldingRef(generateHoldingRef(holding.getName()));
        Holding saved = holdings.save(holding);
        // Opening the position is itself a transaction; invested/quantity then flow from transactions.
        ensureOpeningTransaction(saved);
        syncFromTransactions(saved);
        // FIXED_RATE is valued analytically (see ValuationService) and never snapshotted; everything
        // else needs a first data point for Hot picks to diff future movement against.
        if (saved.getValuationMethod() != ValuationMethod.FIXED_RATE) {
            snapshots.record(SnapshotSubject.HOLDING, saved.getId(), saved.getUser(), zeroIfNull(saved.getCurrentValue()));
        }
        return toResponse(saved);
    }

    /** Persists the user's drag-and-drop order. Unknown/foreign ids are ignored; owned holdings
     *  missing from the list keep their relative order, appended after the ones given. */
    @Transactional
    public List<HoldingResponse> reorder(com.finsights.portfolio.dto.HoldingReorderRequest request) {
        String userId = currentUser.currentUser().getId();
        List<Holding> owned = holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId);
        Map<String, Holding> byId = owned.stream()
                .collect(java.util.stream.Collectors.toMap(Holding::getId, h -> h, (a, b) -> a, LinkedHashMap::new));
        int position = 0;
        for (String id : request.orderedIds()) {
            Holding holding = byId.remove(id);
            if (holding != null) holding.setSortOrder(position++);
        }
        for (Holding leftover : byId.values()) {
            leftover.setSortOrder(position++);
        }
        holdings.saveAll(owned);
        return list();
    }

    @Transactional
    public HoldingResponse update(String id, HoldingRequest request) {
        Holding holding = findOwned(id);
        ValuationMethod oldMethod = holding.getValuationMethod();
        BigDecimal oldValue = holding.getCurrentValue();
        String lockedBroker = holding.getBroker(); // broker is 1-1 with the holding and cannot be re-mapped
        copy(request, holding);
        holding.setBroker(lockedBroker);
        requireUniqueNameAndBroker(currentUser.currentUser().getId(), holding.getName(), lockedBroker, id);
        Holding saved = holdings.save(holding);
        // invested value + quantity are owned by the transaction ledger, not this form.
        syncFromTransactions(saved);
        // Snapshot the new value if it's freshly observable (not FIXED_RATE, which is analytic) and
        // it actually moved — or this is the first snapshot after switching away from FIXED_RATE.
        boolean valueChanged = zeroIfNull(oldValue).compareTo(zeroIfNull(saved.getCurrentValue())) != 0;
        if (saved.getValuationMethod() != ValuationMethod.FIXED_RATE && (valueChanged || oldMethod == ValuationMethod.FIXED_RATE)) {
            snapshots.record(SnapshotSubject.HOLDING, saved.getId(), saved.getUser(), zeroIfNull(saved.getCurrentValue()));
        }
        return toResponse(saved);
    }

    private void validateLiability(HoldingRequest source) {
        if (source.investedValue() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the total borrowed amount");
        }
        if (source.currentValue() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Enter the outstanding amount");
        }
        if (source.repaymentFrequency() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Pick how the loan is repaid");
        }
        if (source.repaymentFrequency() == RepaymentFrequency.ONE_TIME) {
            if (source.repaymentDueDate() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A one-time repayment needs a due date");
            }
        } else {
            if (source.emiDayOfMonth() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set the day of the month the instalment falls due");
            }
            if (source.emiAmount() == null && source.loanTermMonths() == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Enter the instalment amount, or the number of instalments remaining");
            }
        }
    }

    private void requireUniqueNameAndBroker(String userId, String name, String broker, String selfId) {
        holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(userId, name, broker)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> { throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A holding named \"" + name + "\" at \"" + broker + "\" already exists"); });
    }

    private static final java.math.MathContext MC = java.math.MathContext.DECIMAL64;

    /** One purchase lot for FIFO matching. */
    private static final class Lot {
        BigDecimal units;
        BigDecimal costPerUnit;
        Lot(BigDecimal units, BigDecimal costPerUnit) { this.units = units; this.costPerUnit = costPerUnit; }
        BigDecimal cost() { return units.multiply(costPerUnit, MC); }
    }

    /**
     * Recomputes a holding's derived figures from its transaction ledger.
     *
     * <p>Assets — FIFO lots:
     * BUY adds a lot; SELL consumes lots front-to-back and books {@code proceeds − matched cost}
     * as realised P/L; SPLIT scales every lot's units (and shrinks its unit cost); ADJUSTMENT with
     * units adds a lot, without units nudges the invested figure; INTEREST accrues as income that
     * sits on top of current value (never touches cost basis, quantity or realised P/L).
     *
     * <p>Liabilities: invested = total borrowed (Σ BUY); the outstanding balance and REPAY splits
     * are handled in {@link TransactionService}, not here.
     */
    @Transactional
    public void syncFromTransactions(Holding holding) {
        boolean liability = holding.getCategory() != null && holding.getCategory().getKind() == HoldingKind.LIABILITY;
        List<Transaction> ledger = transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(holding.getId());

        if (liability) {
            BigDecimal total = BigDecimal.ZERO;
            for (Transaction t : ledger) {
                if (t.getType() == TransactionType.BUY) total = total.add(zeroIfNull(t.getAmount()));
            }
            holding.setInvestedValue(total.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
            holding.setQuantity(null);
            holding.setAccruedIncome(BigDecimal.ZERO);
            holdings.save(holding);
            return;
        }

        java.util.ArrayDeque<Lot> lots = new java.util.ArrayDeque<>();
        BigDecimal realised = BigDecimal.ZERO;
        BigDecimal accrued = BigDecimal.ZERO;
        BigDecimal basisNudge = BigDecimal.ZERO;   // unit-less ADJUSTMENT amounts
        for (Transaction t : ledger) {
            BigDecimal amount = zeroIfNull(t.getAmount());
            BigDecimal units = t.getQuantity() == null ? BigDecimal.ZERO : t.getQuantity();
            switch (t.getType()) {
                case BUY -> {
                    if (units.signum() > 0) lots.addLast(new Lot(units, amount.divide(units, 10, RoundingMode.HALF_UP)));
                    else basisNudge = basisNudge.add(amount);
                }
                case ADJUSTMENT -> {
                    if (units.signum() > 0) {
                        lots.addLast(new Lot(units, units.signum() == 0 ? BigDecimal.ZERO : amount.divide(units, 10, RoundingMode.HALF_UP)));
                    } else {
                        basisNudge = basisNudge.add(amount);
                    }
                }
                case SELL -> {
                    BigDecimal held = lots.stream().map(l -> l.units).reduce(BigDecimal.ZERO, BigDecimal::add);
                    if (held.signum() <= 0) continue;                 // nothing on the books to sell
                    BigDecimal toSell = units.signum() > 0 ? units.min(held) : held; // no count → close it all
                    BigDecimal matchedCost = BigDecimal.ZERO;
                    BigDecimal remaining = toSell;
                    while (remaining.signum() > 0 && !lots.isEmpty()) {
                        Lot lot = lots.peekFirst();
                        BigDecimal take = remaining.min(lot.units);
                        matchedCost = matchedCost.add(take.multiply(lot.costPerUnit, MC));
                        lot.units = lot.units.subtract(take);
                        remaining = remaining.subtract(take);
                        if (lot.units.signum() <= 0) lots.pollFirst();
                    }
                    realised = realised.add(amount).subtract(matchedCost);
                }
                case SPLIT -> {
                    if (units.signum() > 0) {
                        for (Lot lot : lots) {
                            lot.units = lot.units.multiply(units, MC);
                            lot.costPerUnit = lot.costPerUnit.divide(units, 10, RoundingMode.HALF_UP);
                        }
                    }
                }
                case INTEREST -> accrued = accrued.add(amount);
                case REPAY -> { /* liabilities only — handled elsewhere */ }
            }
        }

        BigDecimal costBasis = lots.stream().map(Lot::cost).reduce(BigDecimal.ZERO, BigDecimal::add).add(basisNudge);
        BigDecimal quantity = lots.stream().map(l -> l.units).reduce(BigDecimal.ZERO, BigDecimal::add);
        holding.setInvestedValue(costBasis.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        holding.setQuantity(quantity.signum() > 0 ? quantity : null);
        holding.setRealisedProfitLoss(realised.setScale(2, RoundingMode.HALF_UP));
        holding.setAccruedIncome(accrued.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        holdings.save(holding);
    }

    /** Records the opening BUY for a holding that has value/units but no transactions yet. */
    public void ensureOpeningTransaction(Holding holding) {
        if (transactions.existsByHolding_Id(holding.getId())) return;
        BigDecimal invested = zeroIfNull(holding.getInvestedValue());
        if (invested.signum() <= 0 && holding.getQuantity() == null) return;
        transactions.save(buildOpeningTransaction(holding, invested, holding.getQuantity()));
    }

    private Transaction buildOpeningTransaction(Holding holding, BigDecimal amount, BigDecimal quantity) {
        Transaction opening = new Transaction();
        opening.setUser(holding.getUser());
        opening.setHolding(holding);
        opening.setType(TransactionType.BUY);
        opening.setAmount(amount);
        opening.setQuantity(quantity);
        opening.setDate(holding.getFixedRateStartDate() != null ? holding.getFixedRateStartDate()
                : holding.getCreatedAt() != null ? holding.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now());
        opening.setNotes("Opening position");
        return opening;
    }

    @Transactional
    public void delete(String id) {
        Holding holding = findOwned(id);
        transactions.deleteByHolding_Id(holding.getId());
        snapshots.deleteFor(SnapshotSubject.HOLDING, holding.getId());
        emiPayments.deleteByHolding_Id(holding.getId());
        holdings.delete(holding);
    }

    public HoldingResponse toResponse(Holding holding) {
        Category category = holding.getCategory();
        BigDecimal invested = zeroIfNull(holding.getInvestedValue());
        BigDecimal current = valuations.currentValue(holding);
        BigDecimal pnl = current.subtract(invested);
        BigDecimal pnlPct = invested.compareTo(BigDecimal.ZERO) == 0 ? BigDecimal.ZERO
                : pnl.divide(invested, 6, RoundingMode.HALF_UP).multiply(BigDecimal.valueOf(100));
        return new HoldingResponse(
                holding.getId(), holding.getHoldingRef(), category.getId(), category.getName(), holding.getName(), category.getKind(),
                holding.getValuationMethod(), holding.getTickerSymbol(), holding.getBroker(),
                holding.getCurrency(), invested, current, pnl, pnlPct, zeroIfNull(holding.getRealisedProfitLoss()),
                zeroIfNull(holding.getAccruedIncome()),
                holding.getQuantity(), holding.getFixedAnnualRate(),
                holding.getCompoundingFrequency(), holding.getFixedRateStartDate(), holding.getFixedRateEndDate(),
                holding.getRepaymentFrequency(), holding.getEmiAmount(), holding.getEmiDayOfMonth(),
                holding.getLoanTermMonths(), holding.getRepaymentDueDate(), holding.getLiquidWithinSevenDays(),
                holding.getBlocked(), holding.getDescription(), holding.getNotes(), Set.copyOf(holding.getTags()),
                holding.getCreatedAt(), holding.getUpdatedAt(), holding.getPriceUpdatedAt());
    }

    private Holding findOwned(String id) {
        return holdings.findByIdAndUser_Id(id, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
    }

    private void copy(HoldingRequest source, Holding target) {
        Category category = categories.findByIdAndUser_Id(source.categoryId(), currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));
        boolean liability = category.getKind() == HoldingKind.LIABILITY;
        // A liability is always manually valued: total borrowed = investedValue, outstanding = currentValue.
        ValuationMethod method = liability || source.valuationMethod() == null
                ? ValuationMethod.MANUAL : source.valuationMethod();

        if (!liability && method == ValuationMethod.FIXED_RATE
                && (source.fixedAnnualRate() == null || source.compoundingFrequency() == null || source.fixedRateStartDate() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Fixed-rate holdings need a rate, an interest payout frequency, and a start date");
        }
        if (!liability && method == ValuationMethod.FIXED_RATE && source.fixedRateEndDate() != null
                && source.fixedRateStartDate() != null && !source.fixedRateEndDate().isAfter(source.fixedRateStartDate())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "The maturity date must be after the start date");
        }
        if (!liability && method == ValuationMethod.MANUAL && source.currentValue() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A manually-valued holding needs a current value");
        }
        if (liability) validateLiability(source);

        target.setCategory(category);
        target.setName(source.name().trim());
        target.setValuationMethod(method);
        target.setTickerSymbol(liability ? null : clean(source.tickerSymbol()));
        target.setBroker(clean(source.broker()));
        target.setCurrency(source.currency() == null || source.currency().isBlank() ? "INR" : source.currency().trim().toUpperCase());
        target.setQuantity(liability ? null : source.quantity());
        target.setInvestedValue(zeroIfNull(source.investedValue()));
        target.setCurrentValue(zeroIfNull(source.currentValue()));
        target.setFixedAnnualRate(source.fixedAnnualRate()); // asset: compounding rate; liability: loan APR
        target.setCompoundingFrequency(liability ? null : source.compoundingFrequency());
        target.setFixedRateStartDate(liability ? null : source.fixedRateStartDate());
        target.setFixedRateEndDate(!liability && method == ValuationMethod.FIXED_RATE ? source.fixedRateEndDate() : null);

        boolean oneTime = liability && source.repaymentFrequency() == RepaymentFrequency.ONE_TIME;
        target.setRepaymentFrequency(liability ? source.repaymentFrequency() : null);
        target.setEmiAmount(liability && !oneTime ? source.emiAmount() : null);
        target.setEmiDayOfMonth(liability && !oneTime ? source.emiDayOfMonth() : null);
        target.setLoanTermMonths(liability && !oneTime ? source.loanTermMonths() : null);
        target.setRepaymentDueDate(oneTime ? source.repaymentDueDate() : null);
        target.setLiquidWithinSevenDays(Boolean.TRUE.equals(source.liquidWithinSevenDays()));
        target.setBlocked(Boolean.TRUE.equals(source.blocked()));
        target.setDescription(clean(source.description()));
        target.setNotes(clean(source.notes()));
        target.setTags(cleanTags(source.tags()));
    }

    private Set<String> cleanTags(Set<String> tags) {
        if (tags == null) return new LinkedHashSet<>();
        return tags.stream().filter(tag -> tag != null && !tag.isBlank()).map(String::trim)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    }

    private String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** kebab-cased name + a short random suffix, e.g. "reliance-industries-4f2a9c" — stable once assigned. */
    private String generateHoldingRef(String name) {
        String base = name == null ? "" : name.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-+|-+$)", "");
        if (base.isBlank()) base = "holding";
        if (base.length() > 40) base = base.substring(0, 40).replaceAll("-+$", "");
        String suffix = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 6);
        return base + "-" + suffix;
    }

    private BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
