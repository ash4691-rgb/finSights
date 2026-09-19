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
 * A single movement threshold per lookback period, owned outright by Hot Picks — a move past it in
 * either direction, up or down, counts. A user with no row here yet reads their legacy values
 * (Platform's per-period Settings fields on {@link UserAccount}) so existing configuration isn't
 * lost; the first save here switches them over to this table for good and gives Settings' fields
 * no further say in Hot Picks.
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
                user.getDailyThresholdPercent(), user.getWeeklyThresholdPercent(), user.getMonthlyThresholdPercent(),
                user.getQuarterlyThresholdPercent(), user.getYearlyThresholdPercent()));
    }

    public MovementThresholdResponse save(MovementThresholdRequest request) {
        UserAccount user = currentUser.currentUser();
        MovementThreshold row = repository.findByUser(user).orElseGet(() -> {
            MovementThreshold created = new MovementThreshold();
            created.setUser(user);
            return created;
        });
        row.setDailyPercent(nonNegative(request.dailyPercent(), "dailyPercent"));
        row.setWeeklyPercent(nonNegative(request.weeklyPercent(), "weeklyPercent"));
        row.setMonthlyPercent(nonNegative(request.monthlyPercent(), "monthlyPercent"));
        row.setQuarterlyPercent(nonNegative(request.quarterlyPercent(), "quarterlyPercent"));
        row.setYearlyPercent(nonNegative(request.yearlyPercent(), "yearlyPercent"));
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
                row.getDailyPercent(), row.getWeeklyPercent(), row.getMonthlyPercent(),
                row.getQuarterlyPercent(), row.getYearlyPercent());
    }
}
