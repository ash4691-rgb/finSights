package com.finsights.portfolio.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Set;

/**
 * A goal with where it stands today and where it's heading. {@code projectedValue} assumes today's
 * value and the monthly saving grow at the expected return until the target date;
 * {@code requiredMonthly} is the saving that would hit the target exactly.
 */
public record GoalResponse(String id, String name, String currency, BigDecimal targetAmount, LocalDate targetDate,
                           BigDecimal monthlyContribution, BigDecimal expectedReturnPercent, Set<String> categoryIds,
                           BigDecimal currentValue, BigDecimal progressPercent, int monthsLeft, BigDecimal projectedValue,
                           boolean onTrack, BigDecimal requiredMonthly) { }
