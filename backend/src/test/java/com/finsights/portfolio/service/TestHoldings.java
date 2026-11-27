package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.HoldingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Set;

/** Compact HoldingResponse builder for service tests. */
final class TestHoldings {
    private String id = "h-1";
    private String name = "Holding";
    private String categoryId = "c-1";
    private String categoryName = "Equity";
    private HoldingKind kind = HoldingKind.ASSET;
    private ValuationMethod method = ValuationMethod.MANUAL;
    private String currency = "INR";
    private BigDecimal invested = BigDecimal.ZERO;
    private BigDecimal current = BigDecimal.ZERO;
    private LocalDate fixedRateEndDate;

    static TestHoldings holding(String id) {
        TestHoldings builder = new TestHoldings();
        builder.id = id;
        builder.name = "Holding " + id;
        return builder;
    }

    TestHoldings name(String value) { name = value; return this; }
    TestHoldings category(String id, String label) { categoryId = id; categoryName = label; return this; }
    TestHoldings kind(HoldingKind value) { kind = value; return this; }
    TestHoldings method(ValuationMethod value) { method = value; return this; }
    TestHoldings currency(String value) { currency = value; return this; }
    TestHoldings invested(String value) { invested = new BigDecimal(value); return this; }
    TestHoldings current(String value) { current = new BigDecimal(value); return this; }
    TestHoldings maturesOn(LocalDate value) { fixedRateEndDate = value; return this; }

    HoldingResponse build() {
        return new HoldingResponse(id, id, categoryId, categoryName, name, kind, method, null, "Kite", currency, currency,
                invested, current, current.subtract(invested), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null, fixedRateEndDate, null, null, null, null, null, false, false,
                null, null, Set.of(), Instant.EPOCH, Instant.EPOCH, null, false, null);
    }
}
