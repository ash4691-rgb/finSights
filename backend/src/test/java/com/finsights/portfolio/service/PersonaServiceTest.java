package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.InvestorPersona;
import com.finsights.portfolio.domain.PortfolioSize;
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.SalaryRange;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.UserPersona;
import com.finsights.portfolio.dto.PersonaDetailsRequest;
import com.finsights.portfolio.dto.PersonaRequest;
import com.finsights.portfolio.dto.PersonaResponse;
import com.finsights.portfolio.dto.PersonaRiskRequest;
import com.finsights.portfolio.repository.UserAccountRepository;
import com.finsights.portfolio.repository.UserPersonaRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class PersonaServiceTest {

    @Mock UserPersonaRepository personas;
    @Mock UserAccountRepository users;
    @Mock CurrentUserService currentUserService;
    @Mock CategoryService categoryService;

    private final UserAccount user = new UserAccount("demo@finsights.local", "Demo");
    private PersonaService service;

    @BeforeEach
    void setUp() {
        service = new PersonaService(personas, users, currentUserService, categoryService);
    }

    // age=30 → derives WEALTH_BUILDER (under 35). Options per risk question: Time Horizon 4,
    // Risk Capacity 5, Risk Tolerance 3, Investment Objectives 3, Liquidity Needs 4 —
    // deliberately mismatched so tests exercise PersonaService.normalize()'s per-question
    // rescaling rather than a uniform 0-2 answer.
    private PersonaRequest request(Integer timeHorizon, Integer riskCapacity, Integer riskTolerance,
                                    Integer investmentObjectives, Integer liquidityNeeds) {
        return new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L, PortfolioSize.L1_TO_10L,
                InvestingTenure.ONE_TO_3_YEARS, Set.of(), Set.of(), timeHorizon, riskCapacity, riskTolerance,
                investmentObjectives, liquidityNeeds);
    }

    @Test
    void lowScoreIsConservative() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        // First option on every question, regardless of how many options it offers.
        PersonaResponse response = service.submit(request(0, 0, 0, 0, 0));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.CONSERVATIVE);
    }

    @Test
    void midScoreIsModerate() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        // The middle-normalizing index for each question's own option count.
        PersonaResponse response = service.submit(request(1, 2, 1, 1, 1));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.MODERATE);
    }

    @Test
    void highScoreIsAggressive() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        // Last option on every question (index = optionCount - 1).
        PersonaResponse response = service.submit(request(3, 4, 2, 2, 3));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
    }

    @Test
    void missingAnswersDefaultToModerateWeight() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        PersonaResponse response = service.submit(request(null, null, null, null, null));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.MODERATE);
    }

    @Test
    void outOfRangeAnswerDefaultsToModerateWeight() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        // riskCapacity only has 5 options (indices 0-4); everything else is a middle-normalizing
        // answer, so a defaulted-to-moderate riskCapacity should still land on MODERATE overall.
        PersonaResponse response = service.submit(request(1, 99, 1, 1, 1));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.MODERATE);
    }

    @Test
    void submitCarriesTenureThroughAndDerivesPersonaFromAge() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        PersonaResponse response = service.submit(request(1, 2, 1, 1, 1));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.WEALTH_BUILDER); // age 30 < 35
        assertThat(response.investingTenure()).isEqualTo(InvestingTenure.ONE_TO_3_YEARS);
        assertThat(response.usedDefaults()).isFalse();
    }

    @Test
    void submitMarksPersonaOnboardingDismissed() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        service.submit(request(1, 2, 1, 1, 1));

        assertThat(user.getPersonaOnboardingDismissed()).isTrue();
        verify(users).save(user);
    }

    // The basic onboarding flow asks no risk questions at all — submit() must leave
    // riskOnboardingDismissed false so the dedicated risk assessment still prompts on the user's
    // next login, even though riskProfile already holds a MODERATE default by then.
    @Test
    void submitLeavesRiskOnboardingUndismissed() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(request(null, null, null, null, null));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.MODERATE);
        assertThat(user.getRiskOnboardingDismissed()).isFalse();
    }

    @Test
    void submitCarriesInstrumentsAndPlatformsThrough() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        when(categoryService.list(null)).thenReturn(List.of());

        PersonaRequest request = new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L, PortfolioSize.L1_TO_10L,
                InvestingTenure.UNDER_1_YEAR, Set.of(InstrumentType.INDIAN_STOCKS, InstrumentType.CRYPTO),
                Set.of("Zerodha", "Groww"), 1, 1, 1, 1, 1);

        PersonaResponse response = service.submit(request);

        assertThat(response.instrumentTypes()).containsExactlyInAnyOrder(InstrumentType.INDIAN_STOCKS, InstrumentType.CRYPTO);
        assertThat(response.platforms()).containsExactlyInAnyOrder("Zerodha", "Groww");
    }

    // --- derivePersona, exercised through submit() since it's private ---

    @Test
    void underThirtyFiveDerivesWealthBuilderRegardlessOfWealth() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(new PersonaRequest(25, null, SalaryRange.ABOVE_50L,
                PortfolioSize.ABOVE_2CR, null, Set.of(), Set.of(), null, null, null, null, null));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.WEALTH_BUILDER);
    }

    @Test
    void fiftyFiveOrOlderDerivesDefensiveConsumerRegardlessOfWealth() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(new PersonaRequest(60, null, SalaryRange.UNDER_5L,
                PortfolioSize.UNDER_1L, null, Set.of(), Set.of(), null, null, null, null, null));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.DEFENSIVE_CONSUMER);
    }

    @Test
    void midAgeWithModeratePortfolioDerivesActiveAccumulator() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(new PersonaRequest(40, null, SalaryRange.L10_TO_25L,
                PortfolioSize.L10_TO_50L, null, Set.of(), Set.of(), null, null, null, null, null));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.ACTIVE_ACCUMULATOR);
    }

    @Test
    void midAgeWithLargePortfolioDerivesHighNetWorthTactician() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(new PersonaRequest(40, null, SalaryRange.L10_TO_25L,
                PortfolioSize.ABOVE_2CR, null, Set.of(), Set.of(), null, null, null, null, null));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.HIGH_NET_WORTH_TACTICIAN);
    }

    @Test
    void midAgeWithTopBracketIncomeDerivesHighNetWorthTacticianEvenWithSmallPortfolio() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(new PersonaRequest(45, null, SalaryRange.ABOVE_50L,
                PortfolioSize.UNDER_1L, null, Set.of(), Set.of(), null, null, null, null, null));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.HIGH_NET_WORTH_TACTICIAN);
    }

    @Test
    void missingAgeDerivesNoPersona() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(new PersonaRequest(null, null, SalaryRange.ABOVE_50L,
                PortfolioSize.ABOVE_2CR, null, Set.of(), Set.of(), null, null, null, null, null));

        assertThat(response.investorPersona()).isNull();
    }

    @Test
    void updateRiskRecomputesRiskProfileAndDismissesRiskOnboarding() {
        when(currentUserService.currentUser()).thenReturn(user);
        UserPersona existing = new UserPersona();
        existing.setRiskProfile(RiskProfile.MODERATE);
        existing.setInvestorPersona(InvestorPersona.WEALTH_BUILDER);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.of(existing));

        // First option on every question — scores CONSERVATIVE.
        PersonaResponse response = service.updateRisk(new PersonaRiskRequest(0, 0, 0, 0, 0));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.CONSERVATIVE);
        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.WEALTH_BUILDER); // untouched
        assertThat(user.getRiskOnboardingDismissed()).isTrue();
        verify(users).save(user);
    }

    @Test
    void updateRiskWithoutAnExistingPersonaIsRejected() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateRisk(new PersonaRiskRequest(0, 0, 0, 0, 0)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void skipSavesModerateDefaultAndDismisses() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        ArgumentCaptor<UserPersona> captor = ArgumentCaptor.forClass(UserPersona.class);

        service.skip();

        assertThat(user.getPersonaOnboardingDismissed()).isTrue();
        verify(personas).save(captor.capture());
        assertThat(captor.getValue().getRiskProfile()).isEqualTo(RiskProfile.MODERATE);
        verify(categoryService, never()).create(any());
    }

    @Test
    void currentIsEmptyBeforeOnboarding() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        assertThat(service.current()).isEmpty();
    }

    @Test
    void currentReturnsSavedPersonaAfterSubmit() {
        when(currentUserService.currentUser()).thenReturn(user);
        UserPersona saved = new UserPersona();
        saved.setRiskProfile(RiskProfile.MODERATE);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.of(saved));

        Optional<PersonaResponse> response = service.current();

        assertThat(response).isPresent();
        assertThat(response.get().riskProfile()).isEqualTo(RiskProfile.MODERATE);
    }

    @Test
    void updateDetailsChangesDemographicsAndRecomputesPersonaButNotRisk() {
        when(currentUserService.currentUser()).thenReturn(user);
        UserPersona existing = new UserPersona();
        existing.setRiskProfile(RiskProfile.AGGRESSIVE);
        existing.setInvestorPersona(InvestorPersona.WEALTH_BUILDER);
        existing.setInstrumentTypes(new java.util.LinkedHashSet<>(Set.of(InstrumentType.CRYPTO)));
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.of(existing));

        // 41 + top-bracket income -> HIGH_NET_WORTH_TACTICIAN, a change from the prior WEALTH_BUILDER.
        PersonaResponse response = service.updateDetails(
                new PersonaDetailsRequest(41, "Doctor", SalaryRange.ABOVE_50L, PortfolioSize.L50_TO_2CR, InvestingTenure.OVER_10_YEARS));

        assertThat(response.age()).isEqualTo(41);
        assertThat(response.occupation()).isEqualTo("Doctor");
        assertThat(response.salaryRange()).isEqualTo(SalaryRange.ABOVE_50L);
        assertThat(response.portfolioSize()).isEqualTo(PortfolioSize.L50_TO_2CR);
        assertThat(response.investingTenure()).isEqualTo(InvestingTenure.OVER_10_YEARS);
        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.HIGH_NET_WORTH_TACTICIAN);
        // Untouched — Settings has no way to resupply the scenario answers.
        assertThat(response.riskProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
        assertThat(response.instrumentTypes()).containsExactly(InstrumentType.CRYPTO);
    }

    @Test
    void updateDetailsWithoutAnExistingPersonaIsRejected() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDetails(
                new PersonaDetailsRequest(41, "Doctor", SalaryRange.ABOVE_50L, PortfolioSize.L50_TO_2CR, InvestingTenure.OVER_10_YEARS)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void seedsOneCategoryPerNewInstrumentType() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        when(categoryService.list(null)).thenReturn(List.of());

        PersonaRequest request = new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L, PortfolioSize.L1_TO_10L,
                InvestingTenure.UNDER_1_YEAR, Set.of(InstrumentType.INDIAN_STOCKS, InstrumentType.CRYPTO),
                Set.of(), 1, 1, 1, 1, 1);

        service.submit(request);

        verify(categoryService).create(argThat(r -> r.name().equals("Domestic Stocks")));
        verify(categoryService).create(argThat(r -> r.name().equals("Crypto")));
    }
}
