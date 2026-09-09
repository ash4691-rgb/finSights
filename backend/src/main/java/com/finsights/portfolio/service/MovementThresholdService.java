package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.MovementThreshold;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.MovementThresholdRequest;
import com.finsights.portfolio.dto.MovementThresholdResponse;
import com.finsights.portfolio.repository.MovementThresholdRepository;
import java.math.BigDecimal;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Up and down movement thresholds per lookback period, owned outright by Top movers. A user with
 * no row here yet reads their legacy up-only values (Platform's per-period Settings fields on
 * {@link UserAccount}) so existing configuration isn't lost; the first save here switches them
 * over to this table for good and gives Settings' fields no further say in Top movers.
 */
@Service
public class MovementThresholdService {
    private final MovementThresholdRepository repository;
    private final CurrentUserService currentUser;

    public MovementThresholdService(MovementThresholdRepository repository, CurrentUserService currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public MovementThresholdResponse get() {
        UserAccount user = currentUser.currentUser();
        return repository.findByUser(user).map(this::toResponse).orElseGet(() -> new MovementThresholdResponse(
                user.getDailyThresholdPercent(), null,
                user.getWeeklyThresholdPercent(), null,
                user.getMonthlyThresholdPercent(), null,
                user.getQuarterlyThresholdPercent(), null,
                user.getYearlyThresholdPercent(), null));
    }

    public MovementThresholdResponse save(MovementThresholdRequest request) {
        UserAccount user = currentUser.currentUser();
        MovementThreshold row = repository.findByUser(user).orElseGet(() -> {
            MovementThreshold created = new MovementThreshold();
            created.setUser(user);
            return created;
        });
        row.setDailyUpPercent(nonNegative(request.dailyUpPercent(), "dailyUpPercent"));
        row.setDailyDownPercent(nonNegative(request.dailyDownPercent(), "dailyDownPercent"));
        row.setWeeklyUpPercent(nonNegative(request.weeklyUpPercent(), "weeklyUpPercent"));
        row.setWeeklyDownPercent(nonNegative(request.weeklyDownPercent(), "weeklyDownPercent"));
        row.setMonthlyUpPercent(nonNegative(request.monthlyUpPercent(), "monthlyUpPercent"));
        row.setMonthlyDownPercent(nonNegative(request.monthlyDownPercent(), "monthlyDownPercent"));
        row.setQuarterlyUpPercent(nonNegative(request.quarterlyUpPercent(), "quarterlyUpPercent"));
        row.setQuarterlyDownPercent(nonNegative(request.quarterlyDownPercent(), "quarterlyDownPercent"));
        row.setYearlyUpPercent(nonNegative(request.yearlyUpPercent(), "yearlyUpPercent"));
        row.setYearlyDownPercent(nonNegative(request.yearlyDownPercent(), "yearlyDownPercent"));
        return toResponse(repository.save(row));
    }

    private BigDecimal nonNegative(BigDecimal value, String field) {
        if (value != null && value.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " cannot be negative");
        }
        return value;
    }

    private MovementThresholdResponse toResponse(MovementThreshold row) {
        return new MovementThresholdResponse(
                row.getDailyUpPercent(), row.getDailyDownPercent(),
                row.getWeeklyUpPercent(), row.getWeeklyDownPercent(),
                row.getMonthlyUpPercent(), row.getMonthlyDownPercent(),
                row.getQuarterlyUpPercent(), row.getQuarterlyDownPercent(),
                row.getYearlyUpPercent(), row.getYearlyDownPercent());
    }
}
