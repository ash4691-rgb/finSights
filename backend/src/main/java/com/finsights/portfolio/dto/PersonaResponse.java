package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.InvestorPersona;
import com.finsights.portfolio.domain.PortfolioSize;
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.SalaryRange;
import java.util.Set;

public record PersonaResponse(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        PortfolioSize portfolioSize,
        InvestorPersona investorPersona,
        InvestingTenure investingTenure,
        Set<InstrumentType> instrumentTypes,
        Set<String> platforms,
        RiskProfile riskProfile,
        boolean usedDefaults
) { }
