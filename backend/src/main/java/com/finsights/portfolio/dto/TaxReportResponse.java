package com.finsights.portfolio.dto;

import com.finsights.portfolio.domain.TaxClass;
import com.finsights.portfolio.tax.TaxRules.Term;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Capital gains and interest for one Indian financial year, in INR. An estimate to plan with, not a
 * tax computation: excludes cess, surcharge and slab-rate income, and nets losses only within each
 * bucket.
 */
public record TaxReportResponse(String financialYear, List<String> availableYears, LocalDate asOf,
                                Summary summary, List<Realised> realised, List<Unrealised> unrealised,
                                List<Interest> interest, List<Classification> holdings, List<String> notes) {

    /** Net gains per bucket, the tax FinSights can estimate on them, and the equity LTCG exemption left. */
    public record Summary(BigDecimal equityShortTerm, BigDecimal equityLongTerm, BigDecimal otherShortTerm,
                          BigDecimal otherLongTerm, BigDecimal debtSlabGains, BigDecimal virtualDigitalAssetGains,
                          BigDecimal interestIncome, BigDecimal equityShortTermTax, BigDecimal equityLongTermTax,
                          BigDecimal otherLongTermTax, BigDecimal virtualDigitalAssetTax, BigDecimal estimatedTax,
                          BigDecimal equityLongTermExemption, BigDecimal equityLongTermExemptionLeft) { }

    /** One FIFO-matched slice of a sale. */
    public record Realised(String holdingId, String name, TaxClass taxClass, LocalDate bought, LocalDate sold,
                           BigDecimal units, BigDecimal cost, BigDecimal proceeds, BigDecimal gain, Term term) { }

    /** Today's paper gain on what's still held, split by term. {@code longTermFrom} is when the next short-term lot turns long-term. */
    public record Unrealised(String holdingId, String name, TaxClass taxClass, BigDecimal shortTermGain,
                             BigDecimal longTermGain, LocalDate longTermFrom) { }

    public record Interest(String holdingId, String name, BigDecimal received, BigDecimal accrued) { }

    /** How each asset holding is treated; {@code inferred} = FinSights guessed it, the user hasn't set it. */
    public record Classification(String holdingId, String name, String categoryName, TaxClass taxClass, boolean inferred) { }
}
