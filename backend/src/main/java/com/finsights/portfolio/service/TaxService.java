package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.Holding;
import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.domain.Transaction;
import com.finsights.portfolio.domain.TransactionType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.TaxReportResponse;
import com.finsights.portfolio.dto.TaxReportResponse.Classification;
import com.finsights.portfolio.dto.TaxReportResponse.Interest;
import com.finsights.portfolio.dto.TaxReportResponse.Realised;
import com.finsights.portfolio.dto.TaxReportResponse.Summary;
import com.finsights.portfolio.dto.TaxReportResponse.Unrealised;
import com.finsights.portfolio.repository.HoldingRepository;
import com.finsights.portfolio.repository.TransactionRepository;
import com.finsights.portfolio.tax.FinancialYear;
import com.finsights.portfolio.tax.TaxClassifier;
import com.finsights.portfolio.tax.TaxLotEngine;
import com.finsights.portfolio.tax.TaxLotEngine.OpenLot;
import com.finsights.portfolio.tax.TaxRules;
import com.finsights.portfolio.tax.TaxRules.Term;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** India tax view: realised gains by term for a financial year, unrealised gains, interest income. */
@Service
public class TaxService {
    static final String INR = "INR";
    static final List<String> NOTES = List.of(
            "An estimate to plan with, not tax advice or a tax computation. Check with a tax professional before filing.",
            "Excludes the 4% cess, surcharge, and anything taxed at your slab rate (debt gains, short-term gains on other assets, interest).",
            "Losses are netted within each bucket only; full set-off and carry-forward rules aren't applied.",
            "Long-term gains on other assets sold before 23 Jul 2024 used 20% with indexation, which isn't estimated here.",
            "Amounts in other currencies are converted at today's rate.");

    private final CurrentUserService currentUser;
    private final HoldingService holdingService;
    private final HoldingRepository holdings;
    private final TransactionRepository transactions;
    private final FxRateService fx;
    private final Clock clock;

    @Autowired
    public TaxService(CurrentUserService currentUser, HoldingService holdingService, HoldingRepository holdings,
                      TransactionRepository transactions, FxRateService fx) {
        this(currentUser, holdingService, holdings, transactions, fx, Clock.systemDefaultZone());
    }

    TaxService(CurrentUserService currentUser, HoldingService holdingService, HoldingRepository holdings,
               TransactionRepository transactions, FxRateService fx, Clock clock) {
        this.currentUser = currentUser;
        this.holdingService = holdingService;
        this.holdings = holdings;
        this.transactions = transactions;
        this.fx = fx;
        this.clock = clock;
    }

    // Not read-only: HoldingService.list() may insert missing opening BUYs.
    @Transactional
    public TaxReportResponse report(String financialYear) {
        UserAccount user = currentUser.currentUser();
        LocalDate today = LocalDate.now(clock);
        FinancialYear current = FinancialYear.of(today);
        FinancialYear year = parseYear(financialYear, current);
        // list() also backfills opening BUYs, so read the ledger after it.
        Map<String, BigDecimal> currentValueInr = holdingService.list(INR).stream()
                .collect(Collectors.toMap(HoldingResponse::id, h -> zeroIfNull(h.currentValue()), (a, b) -> a));
        Map<String, List<Transaction>> ledgers = transactions.findByUser_IdOrderByDateDescCreatedAtDesc(user.getId()).stream()
                .collect(Collectors.groupingBy(t -> t.getHolding().getId()));

        List<Realised> realised = new ArrayList<>();
        List<Unrealised> unrealised = new ArrayList<>();
        List<Interest> interest = new ArrayList<>();
        List<Classification> classes = new ArrayList<>();
        LocalDate earliest = today;
        for (Holding holding : assetsOf(user)) {
            List<Transaction> ledger = oldestFirst(ledgers.getOrDefault(holding.getId(), List.of()));
            TaxClass taxClass = TaxClassifier.effective(holding);
            classes.add(new Classification(holding.getId(), holding.getName(), holding.getCategory().getName(), taxClass,
                    holding.getTaxClass() == null));
            if (!ledger.isEmpty() && ledger.get(0).getDate().isBefore(earliest)) earliest = ledger.get(0).getDate();
            if (taxClass == TaxClass.EXEMPT) continue;

            TaxLotEngine.Result lots = TaxLotEngine.match(ledger.stream().map(TaxService::entryOf).toList());
            realised.addAll(realisedIn(year, holding, taxClass, lots));
            unrealisedOf(holding, taxClass, lots.open(), currentValueInr.get(holding.getId()), today).ifPresent(unrealised::add);
            interestIn(year, holding, ledger).ifPresent(interest::add);
        }
        realised.sort(Comparator.comparing(Realised::sold).thenComparing(Realised::name));
        classes.sort(Comparator.comparing(Classification::name, String.CASE_INSENSITIVE_ORDER));
        return new TaxReportResponse(year.label(), yearsBetween(FinancialYear.of(earliest), current), today,
                summarise(year, realised, interest), realised, unrealised, interest, classes, NOTES);
    }

    @Transactional
    public void setTaxClass(String holdingId, TaxClass taxClass) {
        UserAccount user = currentUser.currentUser();
        Holding holding = holdings.findByIdAndUser_Id(holdingId, user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Holding not found"));
        holding.setTaxClass(taxClass);
        holdings.save(holding);
    }

    private List<Holding> assetsOf(UserAccount user) {
        return holdings.findByUser_IdOrderBySortOrderAscUpdatedAtDesc(user.getId()).stream()
                .filter(h -> h.getCategory() != null && h.getCategory().getKind() == HoldingKind.ASSET)
                .toList();
    }

    private List<Realised> realisedIn(FinancialYear year, Holding holding, TaxClass taxClass, TaxLotEngine.Result lots) {
        return lots.realised().stream()
                .filter(lot -> year.contains(lot.sold()))
                .map(lot -> {
                    BigDecimal cost = inr(lot.cost(), holding);
                    BigDecimal proceeds = inr(lot.proceeds(), holding);
                    return new Realised(holding.getId(), holding.getName(), taxClass, lot.bought(), lot.sold(),
                            lot.units().stripTrailingZeros(), cost, proceeds, proceeds.subtract(cost),
                            TaxRules.term(taxClass, lot.bought(), lot.sold()));
                })
                .toList();
    }

    /** Splits today's value across open lots by units, then each lot's gain into short or long term. */
    private Optional<Unrealised> unrealisedOf(Holding holding, TaxClass taxClass, List<OpenLot> open,
                                                         BigDecimal currentValue, LocalDate today) {
        BigDecimal totalUnits = open.stream().map(OpenLot::units).reduce(BigDecimal.ZERO, BigDecimal::add);
        if (totalUnits.signum() <= 0 || currentValue == null) return Optional.empty();
        BigDecimal shortTerm = BigDecimal.ZERO;
        BigDecimal longTerm = BigDecimal.ZERO;
        LocalDate nextLongTerm = null;
        for (OpenLot lot : open) {
            BigDecimal value = currentValue.multiply(lot.units(), MathContext.DECIMAL64).divide(totalUnits, MathContext.DECIMAL64);
            BigDecimal gain = value.subtract(inr(lot.cost(), holding));
            if (TaxRules.term(taxClass, lot.bought(), today) == Term.SHORT) {
                shortTerm = shortTerm.add(gain);
                nextLongTerm = earlier(nextLongTerm, TaxRules.longTermFrom(taxClass, lot.bought()));
            } else {
                longTerm = longTerm.add(gain);
            }
        }
        BigDecimal total = money(shortTerm.add(longTerm));
        if (TaxRules.longTermAfterMonths(taxClass) == 0) {
            return Optional.of(new Unrealised(holding.getId(), holding.getName(), taxClass, total, null, null, null));
        }
        return Optional.of(new Unrealised(holding.getId(), holding.getName(), taxClass, total, money(shortTerm),
                money(longTerm), nextLongTerm));
    }

    private Optional<Interest> interestIn(FinancialYear year, Holding holding, List<Transaction> ledger) {
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal accrued = BigDecimal.ZERO;
        for (Transaction t : ledger) {
            if (t.getType() != TransactionType.INTEREST || !year.contains(t.getDate())) continue;
            BigDecimal amount = inr(zeroIfNull(t.getAmount()), holding);
            if (t.isInterestPaid()) received = received.add(amount);
            else accrued = accrued.add(amount);
        }
        if (received.signum() == 0 && accrued.signum() == 0) return Optional.empty();
        return Optional.of(new Interest(holding.getId(), holding.getName(), money(received), money(accrued)));
    }

    static Summary summarise(FinancialYear year, List<Realised> realised, List<Interest> interest) {
        BigDecimal equityStPre = sum(realised, r -> isEquity(r, Term.SHORT) && preChange(r));
        BigDecimal equityStPost = sum(realised, r -> isEquity(r, Term.SHORT) && !preChange(r));
        BigDecimal equityLtPre = sum(realised, r -> isEquity(r, Term.LONG) && preChange(r));
        BigDecimal equityLtPost = sum(realised, r -> isEquity(r, Term.LONG) && !preChange(r));
        BigDecimal otherSt = sum(realised, r -> r.taxClass() == TaxClass.OTHER_CAPITAL && r.term() == Term.SHORT);
        BigDecimal otherLtPost = sum(realised, r -> r.taxClass() == TaxClass.OTHER_CAPITAL && r.term() == Term.LONG && !preChange(r));
        BigDecimal otherLt = otherLtPost.add(sum(realised, r -> r.taxClass() == TaxClass.OTHER_CAPITAL && r.term() == Term.LONG && preChange(r)));
        BigDecimal debt = sum(realised, r -> r.term() == Term.SLAB);
        BigDecimal vda = sum(realised, r -> r.term() == Term.FLAT);
        BigDecimal vdaTaxable = realised.stream().filter(r -> r.term() == Term.FLAT).map(Realised::gain)
                .filter(g -> g.signum() > 0).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal stTax = positive(equityStPre).multiply(TaxRules.equityStcgRate(TaxRules.RATE_CHANGE.minusDays(1)))
                .add(positive(equityStPost).multiply(TaxRules.equityStcgRate(TaxRules.RATE_CHANGE)));
        BigDecimal exemption = TaxRules.equityLtcgExemption(year);
        BigDecimal exemptPost = positive(equityLtPost).min(exemption);
        BigDecimal exemptPre = positive(equityLtPre).min(exemption.subtract(exemptPost));
        BigDecimal ltTax = positive(equityLtPost).subtract(exemptPost).multiply(TaxRules.equityLtcgRate(TaxRules.RATE_CHANGE))
                .add(positive(equityLtPre).subtract(exemptPre).multiply(TaxRules.equityLtcgRate(TaxRules.RATE_CHANGE.minusDays(1))));
        BigDecimal otherLtTax = positive(otherLtPost).multiply(TaxRules.otherLtcgRate(TaxRules.RATE_CHANGE));
        BigDecimal vdaTax = vdaTaxable.multiply(TaxRules.VDA_RATE);
        BigDecimal interestIncome = interest.stream().map(i -> i.received().add(i.accrued())).reduce(BigDecimal.ZERO, BigDecimal::add);

        return new Summary(money(equityStPre.add(equityStPost)), money(equityLtPre.add(equityLtPost)), money(otherSt),
                money(otherLt), money(debt), money(vda), money(interestIncome), money(stTax), money(ltTax),
                money(otherLtTax), money(vdaTax), money(stTax.add(ltTax).add(otherLtTax).add(vdaTax)),
                money(exemption), money(exemption.subtract(exemptPost).subtract(exemptPre)));
    }

    static List<String> yearsBetween(FinancialYear first, FinancialYear last) {
        return IntStream.rangeClosed(first.startYear(), last.startYear())
                .mapToObj(y -> new FinancialYear(y).label()).sorted(Comparator.reverseOrder()).toList();
    }

    private static FinancialYear parseYear(String label, FinancialYear fallback) {
        try {
            return FinancialYear.parse(label, fallback);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage());
        }
    }

    private static boolean isEquity(Realised r, Term term) {
        return r.taxClass() == TaxClass.LISTED_EQUITY && r.term() == term;
    }

    private static boolean preChange(Realised r) {
        return r.sold().isBefore(TaxRules.RATE_CHANGE);
    }

    private static BigDecimal sum(List<Realised> rows, Predicate<Realised> filter) {
        return rows.stream().filter(filter).map(Realised::gain).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<Transaction> oldestFirst(List<Transaction> ledger) {
        return ledger.stream().sorted(Comparator.comparing(Transaction::getDate)
                .thenComparing(Transaction::getCreatedAt, Comparator.nullsFirst(Comparator.naturalOrder()))).toList();
    }

    private static TaxLotEngine.Entry entryOf(Transaction t) {
        return new TaxLotEngine.Entry(t.getDate(), t.getType(), t.getAmount(), t.getQuantity());
    }

    private BigDecimal inr(BigDecimal amount, Holding holding) {
        String currency = holding.getCurrency() == null ? INR : holding.getCurrency();
        return money(fx.convert(amount, currency, INR));
    }

    private static LocalDate earlier(LocalDate a, LocalDate b) {
        if (a == null) return b;
        return a.isBefore(b) ? a : b;
    }

    private static BigDecimal positive(BigDecimal value) {
        return value.max(BigDecimal.ZERO);
    }

    private static BigDecimal money(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
