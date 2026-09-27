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
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.SalaryRange;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.UserPersona;
import com.finsights.portfolio.dto.PersonaDetailsRequest;
import com.finsights.portfolio.dto.PersonaRequest;
import com.finsights.portfolio.dto.PersonaResponse;
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

    // Options per question: Time Horizon 4, Risk Capacity 5, Risk Tolerance 3,
    // Investment Objectives 3, Liquidity Needs 4 — deliberately mismatched so tests exercise
    // PersonaService.normalize()'s per-question rescaling rather than a uniform 0-2 answer.
    private PersonaRequest request(Integer timeHorizon, Integer riskCapacity, Integer riskTolerance,
                                    Integer investmentObjectives, Integer liquidityNeeds) {
        return new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L, InvestorPersona.ACTIVE_ACCUMULATOR,
                InvestingTenure.ONE_TO_3_YEARS, Set.of(), timeHorizon, riskCapacity, riskTolerance,
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
    void submitCarriesPersonaAndTenureThrough() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        PersonaResponse response = service.submit(request(1, 2, 1, 1, 1));

        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.ACTIVE_ACCUMULATOR);
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
    void updateDetailsChangesOnlyDemographicsNotRiskOrPersona() {
        when(currentUserService.currentUser()).thenReturn(user);
        UserPersona existing = new UserPersona();
        existing.setRiskProfile(RiskProfile.AGGRESSIVE);
        existing.setInvestorPersona(InvestorPersona.HIGH_NET_WORTH_TACTICIAN);
        existing.setInstrumentTypes(new java.util.LinkedHashSet<>(Set.of(InstrumentType.CRYPTO)));
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.of(existing));

        PersonaResponse response = service.updateDetails(
                new PersonaDetailsRequest(41, "Doctor", SalaryRange.ABOVE_50L, InvestingTenure.OVER_10_YEARS));

        assertThat(response.age()).isEqualTo(41);
        assertThat(response.occupation()).isEqualTo("Doctor");
        assertThat(response.salaryRange()).isEqualTo(SalaryRange.ABOVE_50L);
        assertThat(response.investingTenure()).isEqualTo(InvestingTenure.OVER_10_YEARS);
        // Untouched — Settings has no way to resupply the scenario answers or re-ask the persona question.
        assertThat(response.riskProfile()).isEqualTo(RiskProfile.AGGRESSIVE);
        assertThat(response.investorPersona()).isEqualTo(InvestorPersona.HIGH_NET_WORTH_TACTICIAN);
        assertThat(response.instrumentTypes()).containsExactly(InstrumentType.CRYPTO);
    }

    @Test
    void updateDetailsWithoutAnExistingPersonaIsRejected() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.updateDetails(
                new PersonaDetailsRequest(41, "Doctor", SalaryRange.ABOVE_50L, InvestingTenure.OVER_10_YEARS)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("404");
    }

    @Test
    void seedsOneCategoryPerNewInstrumentType() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        when(categoryService.list(null)).thenReturn(List.of());

        PersonaRequest request = new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L,
                InvestorPersona.WEALTH_BUILDER, InvestingTenure.UNDER_1_YEAR,
                Set.of(InstrumentType.INDIAN_STOCKS, InstrumentType.CRYPTO), 1, 1, 1, 1, 1);

        service.submit(request);

        verify(categoryService).create(argThat(r -> r.name().equals("Indian Stocks")));
        verify(categoryService).create(argThat(r -> r.name().equals("Crypto")));
    }
}
