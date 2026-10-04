package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.PortfolioSize;
import com.finsights.portfolio.domain.SalaryRange;

/** Settings' lightweight "just the demographics" edit — deliberately narrower than
 *  {@link PersonaRequest}: it never touches riskProfile/instrumentTypes, so editing your age
 *  from Settings can't silently reset a risk profile that took five scenario questions to
 *  compute (Settings has no way to resupply those answers). investorPersona DOES get
 *  recomputed here, same as on initial submit, since age/salaryRange/portfolioSize are exactly
 *  its inputs — see PersonaService.updateDetails / derivePersona. */
public record PersonaDetailsRequest(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        PortfolioSize portfolioSize,
        InvestingTenure investingTenure
) { }
