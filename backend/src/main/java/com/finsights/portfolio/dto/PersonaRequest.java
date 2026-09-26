package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.InstrumentType;
import com.finsights.portfolio.domain.SalaryRange;
import java.util.Set;

/** Every field is optional — the persona questionnaire is skippable, so nothing here is
 *  required at the HTTP layer; PersonaService fills in sensible defaults for anything missing. */
public record PersonaRequest(
        Integer age,
        String occupation,
        SalaryRange salaryRange,
        Set<InstrumentType> instrumentTypes,
        Integer marketDropAnswer,
        Integer timeHorizonAnswer,
        Integer tradeOffAnswer
) { }
