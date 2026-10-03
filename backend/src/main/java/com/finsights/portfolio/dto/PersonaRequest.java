package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.InvestorPersona;
import com.finsights.portfolio.domain.SalaryRange;
import java.util.Set;

/** Every field is optional — the persona questionnaire is skippable, so nothing here is
 *  required at the HTTP layer; PersonaService fills in sensible defaults for anything missing.
 *  The five *Answer fields are each the 0-based index of the option the user picked for that
 *  question — each question offers a different number of options (see the framework in
 *  risk-assessment.tsx's SCENARIOS), so PersonaService.scoreRisk normalizes each one against
 *  its own option count before combining them into a RiskProfile. The basic onboarding flow
 *  (persona-onboarding.tsx) no longer asks these at all — they're left null there, which scores
 *  as a MODERATE default until the user completes the dedicated risk assessment (see
 *  PersonaRiskRequest / PersonaService.updateRisk) on a later login. */
public record PersonaRequest(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        InvestorPersona investorPersona,
        InvestingTenure investingTenure,
        Set<InstrumentType> instrumentTypes,
        Set<InstrumentType> interestedInstrumentTypes,
        Set<String> platforms,
        Integer timeHorizonAnswer,
        Integer riskCapacityAnswer,
        Integer riskToleranceAnswer,
        Integer investmentObjectivesAnswer,
        Integer liquidityNeedsAnswer
) { }
