package com.finsights.portfolio.dto;

/** The five risk-scenario answers on their own — used both by Settings' "Reassess risk profile"
 *  and the dedicated risk-assessment prompt shown on a later login (see PersonaService.updateRisk).
 *  Deliberately narrower than PersonaRequest: it never touches demographics, persona, instruments
 *  or platforms, which the risk-assessment screen has no way to resupply. */
public record PersonaRiskRequest(
        Integer timeHorizonAnswer,
        Integer riskCapacityAnswer,
        Integer riskToleranceAnswer,
        Integer investmentObjectivesAnswer,
        Integer liquidityNeedsAnswer
) { }
