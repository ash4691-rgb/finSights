package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.InvestorPersona;
import com.finsights.portfolio.domain.SalaryRange;
import java.util.Set;

/** Every field is optional — the persona questionnaire is skippable, so nothing here is
 *  required at the HTTP layer; PersonaService fills in sensible defaults for anything missing.
 *  The five *Answer fields are each 0 (conservative-leaning), 1 (moderate) or 2 (aggressive-
 *  leaning) — see PersonaService.scoreRisk for how they're combined into a RiskProfile. */
public record PersonaRequest(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        InvestorPersona investorPersona,
        InvestingTenure investingTenure,
        Set<InstrumentType> instrumentTypes,
        Integer marketDropAnswer,
        Integer timeHorizonAnswer,
        Integer tradeOffAnswer,
        Integer volatilityReactionAnswer,
        Integer primaryGoalAnswer
) { }
