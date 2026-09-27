package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.SalaryRange;

/** Settings' lightweight "just the demographics" edit — deliberately narrower than
 *  {@link PersonaRequest}: it never touches riskProfile/investorPersona/instrumentTypes, so
 *  editing your age from Settings can't silently reset a risk profile that took five scenario
 *  questions to compute (Settings has no way to resupply those answers). See
 *  PersonaService.updateDetails. */
public record PersonaDetailsRequest(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        InvestingTenure investingTenure
) { }
