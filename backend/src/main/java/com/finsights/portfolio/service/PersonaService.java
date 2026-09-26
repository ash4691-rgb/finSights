package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.UserPersona;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.CategoryRequest;
import com.finsights.portfolio.dto.PersonaRequest;
import com.finsights.portfolio.dto.PersonaResponse;
import com.finsights.portfolio.repository.UserAccountRepository;
import com.finsights.portfolio.repository.UserPersonaRepository;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Persona onboarding: a few questions used to seed starter categories and compute a risk
 *  profile from three scenario answers. Skippable — dismissing without completing saves a
 *  MODERATE default instead of leaving the user with no persona at all. */
@Service
public class PersonaService {
    private static final Map<InstrumentType, String> CATEGORY_NAMES = Map.of(
            InstrumentType.INDIAN_STOCKS, "Indian Stocks",
            InstrumentType.FOREIGN_STOCKS, "Foreign Stocks",
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
        Set<InstrumentType> instruments = request.instrumentTypes() == null ? Set.of() : request.instrumentTypes();
        persona.setInstrumentTypes(new LinkedHashSet<>(instruments));
        persona.setRiskProfile(scoreRisk(request.marketDropAnswer(), request.timeHorizonAnswer(), request.tradeOffAnswer()));
        persona.setUsedDefaults(false);
        personas.save(persona);

        seedCategories(instruments);

        user.setPersonaOnboardingDismissed(true);
        users.save(user);
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

    /** Each scenario answer is 0 (conservative-leaning), 1 (moderate) or 2 (aggressive-leaning);
     *  a missing answer defaults to 1 (moderate) rather than skewing the score toward either end. */
    private RiskProfile scoreRisk(Integer marketDrop, Integer timeHorizon, Integer tradeOff) {
        int total = normalize(marketDrop) + normalize(timeHorizon) + normalize(tradeOff);
        if (total <= 1) return RiskProfile.CONSERVATIVE;
        if (total <= 4) return RiskProfile.MODERATE;
        return RiskProfile.AGGRESSIVE;
    }

    private int normalize(Integer answer) {
        return answer == null || answer < 0 || answer > 2 ? 1 : answer;
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
                persona.getInstrumentTypes(), persona.getRiskProfile(), persona.isUsedDefaults());
    }
}
