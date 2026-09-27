package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.InvestorExperience;
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.SalaryRange;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.UserPersona;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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

    private PersonaRequest request(Integer marketDrop, Integer timeHorizon, Integer tradeOff,
                                    Integer volatilityReaction, Integer primaryGoal) {
        return new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L, InvestorExperience.MODERATE,
                InvestingTenure.ONE_TO_3_YEARS, Set.of(), marketDrop, timeHorizon, tradeOff,
                volatilityReaction, primaryGoal);
    }

    @Test
    void lowScoreIsConservative() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(request(0, 0, 0, 0, 0));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.CONSERVATIVE);
    }

    @Test
    void midScoreIsModerate() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        PersonaResponse response = service.submit(request(1, 1, 1, 1, 1));

        assertThat(response.riskProfile()).isEqualTo(RiskProfile.MODERATE);
    }

    @Test
    void highScoreIsAggressive() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        PersonaResponse response = service.submit(request(2, 2, 2, 2, 2));

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
    void submitCarriesExperienceAndTenureThrough() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        PersonaResponse response = service.submit(request(1, 1, 1, 1, 1));

        assertThat(response.investorExperience()).isEqualTo(InvestorExperience.MODERATE);
        assertThat(response.investingTenure()).isEqualTo(InvestingTenure.ONE_TO_3_YEARS);
        assertThat(response.usedDefaults()).isFalse();
    }

    @Test
    void submitMarksPersonaOnboardingDismissed() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        service.submit(request(1, 1, 1, 1, 1));

        assertThat(user.getPersonaOnboardingDismissed()).isTrue();
        verify(users).save(user);
    }

    @Test
    void skipSavesModerateDefaultAndDismisses() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());

        service.skip();

        assertThat(user.getPersonaOnboardingDismissed()).isTrue();
        verify(personas).save(any());
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
    void seedsOneCategoryPerNewInstrumentType() {
        when(currentUserService.currentUser()).thenReturn(user);
        when(personas.findByUser_Id(user.getId())).thenReturn(Optional.empty());
        when(categoryService.list(null)).thenReturn(List.of());

        PersonaRequest request = new PersonaRequest(30, "Engineer", SalaryRange.L10_TO_25L,
                InvestorExperience.NEWBIE, InvestingTenure.UNDER_1_YEAR,
                Set.of(InstrumentType.INDIAN_STOCKS, InstrumentType.CRYPTO), 1, 1, 1, 1, 1);

        service.submit(request);

        verify(categoryService).create(argThat(r -> r.name().equals("Indian Stocks")));
        verify(categoryService).create(argThat(r -> r.name().equals("Crypto")));
    }
}
