package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Money-weighted returns (XIRR) for the signed-in user's assets, in {@code currency}.
 * {@code xirrPercent} is null when it can't be computed meaningfully; {@code note} says why.
 */
public record ReturnsResponse(String currency, LocalDate asOf, Figure portfolio, List<Group> categories, List<Item> holdings) {

    /** {@code netInvested} = money put in minus money taken out; {@code gain} = currentValue − netInvested. */
    public record Figure(BigDecimal xirrPercent, BigDecimal netInvested, BigDecimal currentValue, BigDecimal gain,
                         LocalDate since, String note) { }

    public record Group(String categoryId, String categoryName, Figure figure) { }

    public record Item(String holdingId, String name, String categoryName, Figure figure) { }
}
