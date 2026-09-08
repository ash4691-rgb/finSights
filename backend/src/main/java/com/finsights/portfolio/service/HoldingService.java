package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Category;
import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.SnapshotSubject;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingRequest;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.repository.CategoryRepository;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    public HoldingService(HoldingRepository holdings, CategoryRepository categories, CurrentUserService currentUser,
                          ValuationService valuations, FxRateService fx, TransactionRepository transactions,
                          PriceSnapshotService snapshots) {
        this.holdings = holdings;
        this.categories = categories;
        this.currentUser = currentUser;
        this.valuations = valuations;
        this.fx = fx;
        this.transactions = transactions;
        this.snapshots = snapshots;
    }

    @Transactional
    public List<HoldingResponse> list() {
        String userId = currentUser.currentUser().getId();
        List<Holding> owned = holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(userId);
        backfillHoldingRefs(owned);
        backfillOpeningTransactions(owned, userId);
        return owned.stream().map(this::toResponse).toList();
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

    private void requireUniqueNameAndBroker(String userId, String name, String broker, String selfId) {
        holdings.findByUser_IdAndNameIgnoreCaseAndBrokerIgnoreCase(userId, name, broker)
                .filter(existing -> !existing.getId().equals(selfId))
                .ifPresent(existing -> { throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "A holding named \"" + name + "\" at \"" + broker + "\" already exists"); });
    }

    /** Recomputes invested value + quantity from this holding's transaction ledger. */
    @Transactional
    public void syncFromTransactions(Holding holding) {
        BigDecimal invested = BigDecimal.ZERO;
        BigDecimal quantity = BigDecimal.ZERO;
        for (Transaction t : transactions.findByHolding_IdOrderByDateAscCreatedAtAsc(holding.getId())) {
            BigDecimal amount = zeroIfNull(t.getAmount());
            BigDecimal units = t.getQuantity() == null ? BigDecimal.ZERO : t.getQuantity();
            switch (t.getType()) {
                case BUY, ADJUSTMENT -> { invested = invested.add(amount); quantity = quantity.add(units); }
                case SELL -> { invested = invested.subtract(amount); quantity = quantity.subtract(units); }
                case SPLIT -> { if (units.signum() > 0) quantity = quantity.multiply(units); }
                case INTEREST -> { /* income — affects returns, not cost basis or units held */ }
            }
        }
        holding.setInvestedValue(invested.max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP));
        holding.setQuantity(quantity.signum() > 0 ? quantity : null);
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
                holding.getValuationMethod(), holding.getTickerSymbol(), holding.getBroker(), holding.getOwnerName(),
                holding.getCurrency(), invested, current, pnl, pnlPct, holding.getQuantity(), holding.getFixedAnnualRate(),
                holding.getCompoundingFrequency(), holding.getFixedRateStartDate(), holding.getLiquidWithinSevenDays(),
                holding.getBlocked(), holding.getDescription(), holding.getNotes(), Set.copyOf(holding.getTags()),
                holding.getCreatedAt(), holding.getUpdatedAt());
    }

    private Holding findOwned(String id) {
        return holdings.findByIdAndUser_Id(id, currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
    }

    private void copy(HoldingRequest source, Holding target) {
        Category category = categories.findByIdAndUser_Id(source.categoryId(), currentUser.currentUser().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));
        if (source.valuationMethod() == ValuationMethod.FIXED_RATE
                && (source.fixedAnnualRate() == null || source.compoundingFrequency() == null || source.fixedRateStartDate() == null)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Fixed-rate holdings require rate, compounding frequency, and start date");
        }
        target.setCategory(category);
        target.setName(source.name().trim());
        target.setValuationMethod(source.valuationMethod());
        target.setTickerSymbol(clean(source.tickerSymbol()));
        target.setBroker(clean(source.broker()));
        target.setOwnerName(clean(source.ownerName()));
        target.setCurrency(source.currency() == null || source.currency().isBlank() ? "INR" : source.currency().trim().toUpperCase());
        target.setQuantity(source.quantity());
        target.setInvestedValue(zeroIfNull(source.investedValue()));
        target.setCurrentValue(zeroIfNull(source.currentValue()));
        target.setFixedAnnualRate(source.fixedAnnualRate());
        target.setCompoundingFrequency(source.compoundingFrequency());
        target.setFixedRateStartDate(source.fixedRateStartDate());
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
