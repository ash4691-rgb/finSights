package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.InvestingTenure;
import com.finsights.portfolio.domain.InvestorExperience;
import com.finsights.portfolio.domain.RiskProfile;
import com.finsights.portfolio.domain.SalaryRange;
import java.util.Set;

public record PersonaResponse(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        InvestorExperience investorExperience,
        InvestingTenure investingTenure,
        Set<InstrumentType> instrumentTypes,
        RiskProfile riskProfile,
        boolean usedDefaults
) { }
