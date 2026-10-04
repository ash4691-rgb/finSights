package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestorPersona;
import com.finsights.portfolio.domain.PortfolioSize;
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.SalaryRange;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.UserPersona;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.PersonaDetailsRequest;
import com.finsights.portfolio.dto.PersonaRequest;
import com.finsights.portfolio.dto.PersonaResponse;
import com.finsights.portfolio.dto.PersonaRiskRequest;
import com.finsights.portfolio.repository.UserAccountRepository;
import com.finsights.portfolio.repository.UserPersonaRepository;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Persona onboarding: a few questions used to seed starter categories and derive an
 *  InvestorPersona archetype from age/wealth signals (see derivePersona) — the risk profile
 *  itself comes from a separate five-question assessment (see updateRisk). Skippable —
 *  dismissing without completing saves a MODERATE default instead of leaving the user with no
 *  persona at all. */
@Service
public class PersonaService {
    // "Domestic"/"International" rather than a literal country name — these feed straight into
    // seedCategories() below with no notion of which country the user is in, and (like the
    // onboarding instrument checklist itself, see persona-onboarding.tsx's INSTRUMENT_OPTIONS)
    // "domestic" is relative to the user by construction, so it's correct for every country
    // without the category-naming code needing to know what that country is.
    private static final Map<InstrumentType, String> CATEGORY_NAMES = Map.of(
            InstrumentType.INDIAN_STOCKS, "Domestic Stocks",
            InstrumentType.FOREIGN_STOCKS, "International Stocks",
            InstrumentType.MUTUAL_FUNDS, "Mutual Funds",
            InstrumentType.CRYPTO, "Crypto",
            InstrumentType.COMMODITIES, "Commodities",
            InstrumentType.FIXED_RETURN, "Fixed Return Instruments",
            InstrumentType.REAL_ESTATE, "Real Estate"
    );
    private static final Map<InstrumentType, ValuationMethod> CATEGORY_VALUATION = Map.of(
            InstrumentType.INDIAN_STOCKS, ValuationMethod.MARKET_PRICE,
            InstrumentType.FOREIGN_STOCKS, ValuationMethod.MARKET_PRICE,
            InstrumentType.MUTUAL_FUNDS, ValuationMethod.MARKET_PRICE,
            InstrumentType.CRYPTO, ValuationMethod.MARKET_PRICE,
            InstrumentType.COMMODITIES, ValuationMethod.MARKET_PRICE,
            InstrumentType.FIXED_RETURN, ValuationMethod.FIXED_RATE,
            InstrumentType.REAL_ESTATE, ValuationMethod.MANUAL
    );

    private final UserPersonaRepository personas;
    private final UserAccountRepository users;
    private final CurrentUserService currentUser;
    private final CategoryService categoryService;

    public PersonaService(UserPersonaRepository personas, UserAccountRepository users,
                           CurrentUserService currentUser, CategoryService categoryService) {
        this.personas = personas;
        this.users = users;
        this.currentUser = currentUser;
        this.categoryService = categoryService;
    }

    @Transactional
    public PersonaResponse submit(PersonaRequest request) {
        UserAccount user = currentUser.currentUser();
        UserPersona persona = personas.findByUser_Id(user.getId()).orElseGet(UserPersona::new);
        persona.setUser(user);
        persona.setAge(request.age());
        persona.setOccupation(request.occupation());
        persona.setSalaryRange(request.salaryRange());
        persona.setPortfolioSize(request.portfolioSize());
        persona.setInvestorPersona(derivePersona(request.age(), request.salaryRange(), request.portfolioSize()));
        persona.setInvestingTenure(request.investingTenure());
        Set<InstrumentType> instruments = request.instrumentTypes() == null ? Set.of() : request.instrumentTypes();
        persona.setInstrumentTypes(new LinkedHashSet<>(instruments));
        persona.setPlatforms(new LinkedHashSet<>(request.platforms() == null ? Set.of() : request.platforms()));
        // The basic onboarding flow asks no risk questions at all (they're null here) — that
        // scores as a MODERATE default, same as the skip() path below, until updateRisk() runs
        // the dedicated assessment on a later login.
        persona.setRiskProfile(scoreRisk(request.timeHorizonAnswer(), request.riskCapacityAnswer(),
                request.riskToleranceAnswer(), request.investmentObjectivesAnswer(), request.liquidityNeedsAnswer()));
        persona.setUsedDefaults(false);
        personas.save(persona);

        seedCategories(instruments);

        user.setPersonaOnboardingDismissed(true);
        users.save(user);
        return toResponse(persona);
    }

    /** The five-question risk assessment on its own — shown on a login after the basic
     *  onboarding is done (see UserAccount.riskOnboardingDismissed) or re-run anytime from
     *  Settings' "Reassess risk profile". Only riskProfile and the dismissal flag change. */
    @Transactional
    public PersonaResponse updateRisk(PersonaRiskRequest request) {
        UserAccount user = currentUser.currentUser();
        UserPersona persona = personas.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Complete the persona questionnaire before assessing risk"));
        persona.setRiskProfile(scoreRisk(request.timeHorizonAnswer(), request.riskCapacityAnswer(),
                request.riskToleranceAnswer(), request.investmentObjectivesAnswer(), request.liquidityNeedsAnswer()));
        personas.save(persona);

        user.setRiskOnboardingDismissed(true);
        users.save(user);
        return toResponse(persona);
    }

    /** Nullable — no persona row exists until the user has either completed or skipped the
     *  questionnaire at least once. */
    public Optional<PersonaResponse> current() {
        UserAccount user = currentUser.currentUser();
        return personas.findByUser_Id(user.getId()).map(this::toResponse);
    }

    /** Settings' inline "edit your details" path — updates age/occupation/salaryRange/
     *  portfolioSize/investingTenure, leaving riskProfile, instrumentTypes and usedDefaults
     *  exactly as they were. investorPersona IS recomputed here (age/salaryRange/portfolioSize
     *  are exactly its inputs — see derivePersona), so it stays in sync with whatever the user
     *  just edited. Requires an existing persona row (Settings only offers this once the
     *  questionnaire has been completed or skipped at least once — see PersonaController). */
    @Transactional
    public PersonaResponse updateDetails(PersonaDetailsRequest request) {
        UserAccount user = currentUser.currentUser();
        UserPersona persona = personas.findByUser_Id(user.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Complete the persona questionnaire before editing these details"));
        persona.setAge(request.age());
        persona.setOccupation(request.occupation());
        persona.setSalaryRange(request.salaryRange());
        persona.setPortfolioSize(request.portfolioSize());
        persona.setInvestingTenure(request.investingTenure());
        persona.setInvestorPersona(derivePersona(request.age(), request.salaryRange(), request.portfolioSize()));
        personas.save(persona);
        return toResponse(persona);
    }

    @Transactional
    public void skip() {
        UserAccount user = currentUser.currentUser();
        UserPersona persona = personas.findByUser_Id(user.getId()).orElseGet(UserPersona::new);
        persona.setUser(user);
        persona.setRiskProfile(RiskProfile.MODERATE);
        persona.setUsedDefaults(true);
        personas.save(persona);

        user.setPersonaOnboardingDismissed(true);
        users.save(user);
    }

    // Option count per question in the framework — Time Horizon and Liquidity Needs offer 4,
    // Risk Capacity offers 5, Risk Tolerance and Investment Objectives offer 3. Kept in the same
    // order scoreRisk takes its answers, purely so the two stay easy to eyeball together.
    private static final int TIME_HORIZON_OPTIONS = 4;
    private static final int RISK_CAPACITY_OPTIONS = 5;
    private static final int RISK_TOLERANCE_OPTIONS = 3;
    private static final int INVESTMENT_OBJECTIVES_OPTIONS = 3;
    private static final int LIQUIDITY_NEEDS_OPTIONS = 4;

    /** Each answer is the 0-based index the user picked among that question's own options, which
     *  differ in count across the five questions (3 to 5) — normalize() rescales each one to a
     *  common 0 (conservative-leaning) - 2 (aggressive-leaning) score before summing, so a
     *  5-option question doesn't quietly outweigh a 3-option one. A missing answer defaults to
     *  the normalized midpoint (1, moderate) rather than skewing the score toward either end.
     *  Five questions (0-10 total) split roughly into thirds. */
    private RiskProfile scoreRisk(Integer timeHorizon, Integer riskCapacity, Integer riskTolerance,
                                   Integer investmentObjectives, Integer liquidityNeeds) {
        int total = normalize(timeHorizon, TIME_HORIZON_OPTIONS) + normalize(riskCapacity, RISK_CAPACITY_OPTIONS)
                + normalize(riskTolerance, RISK_TOLERANCE_OPTIONS) + normalize(investmentObjectives, INVESTMENT_OBJECTIVES_OPTIONS)
                + normalize(liquidityNeeds, LIQUIDITY_NEEDS_OPTIONS);
        if (total <= 3) return RiskProfile.CONSERVATIVE;
        if (total <= 6) return RiskProfile.MODERATE;
        return RiskProfile.AGGRESSIVE;
    }

    /** Rescales a 0-based option index from a scale of {@code optionCount} options onto 0-2,
     *  rounding to the nearest whole score. A null or out-of-range answer normalizes to 1. */
    private int normalize(Integer answer, int optionCount) {
        if (answer == null || answer < 0 || answer >= optionCount) return 1;
        return (int) Math.round(answer * 2.0 / (optionCount - 1));
    }

    private static final Set<PortfolioSize> HIGH_WEALTH_PORTFOLIO = Set.of(PortfolioSize.L50_TO_2CR, PortfolioSize.ABOVE_2CR);

    /** Picks an InvestorPersona archetype from age and wealth signals instead of asking the user
     *  to self-select one — age bands match the framework documented on InvestorPersona itself:
     *  under 35 → WEALTH_BUILDER, 55+ → DEFENSIVE_CONSUMER, and the 35-54 band in between splits
     *  on wealth (a large portfolio or top-bracket income) into HIGH_NET_WORTH_TACTICIAN vs. the
     *  more typical ACTIVE_ACCUMULATOR. Null age means not enough signal yet — no persona row has
     *  reached the demographics step — so this returns null rather than guessing. */
    private InvestorPersona derivePersona(Integer age, SalaryRange salaryRange, PortfolioSize portfolioSize) {
        if (age == null) return null;
        if (age >= 55) return InvestorPersona.DEFENSIVE_CONSUMER;
        if (age < 35) return InvestorPersona.WEALTH_BUILDER;
        boolean highWealth = HIGH_WEALTH_PORTFOLIO.contains(portfolioSize) || salaryRange == SalaryRange.ABOVE_50L;
        return highWealth ? InvestorPersona.HIGH_NET_WORTH_TACTICIAN : InvestorPersona.ACTIVE_ACCUMULATOR;
    }

    /** One starter category per selected instrument type the user doesn't already have a
     *  category for (matched by name) — a personalised head start, not a rigid taxonomy: every
     *  category created here is a completely ordinary, editable/deletable one afterward. */
    private void seedCategories(Set<InstrumentType> instrumentTypes) {
        if (instrumentTypes.isEmpty()) return;
        Set<String> existingNames = categoryService.list(null).stream()
                .map(c -> c.name().toLowerCase())
                .collect(Collectors.toSet());
        for (InstrumentType type : instrumentTypes) {
            String name = CATEGORY_NAMES.get(type);
            if (name == null || existingNames.contains(name.toLowerCase())) continue;
            categoryService.create(new CategoryRequest(name, HoldingKind.ASSET, null,
                    Set.of(CATEGORY_VALUATION.getOrDefault(type, ValuationMethod.MANUAL))));
        }
    }

    private PersonaResponse toResponse(UserPersona persona) {
        return new PersonaResponse(persona.getAge(), persona.getOccupation(), persona.getSalaryRange(),
                persona.getPortfolioSize(), persona.getInvestorPersona(), persona.getInvestingTenure(),
                persona.getInstrumentTypes(), persona.getPlatforms(),
                persona.getRiskProfile(), persona.isUsedDefaults());
    }
}
