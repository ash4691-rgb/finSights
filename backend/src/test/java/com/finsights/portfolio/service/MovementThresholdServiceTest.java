package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.MovementThreshold;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.dto.MovementThresholdRequest;
import com.finsights.portfolio.dto.MovementThresholdResponse;
import com.finsights.portfolio.repository.MovementThresholdRepository;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class MovementThresholdServiceTest {

    @Mock MovementThresholdRepository repository;
    @Mock CurrentUserService currentUser;
    private MovementThresholdService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new MovementThresholdService(repository, currentUser);
        user = new UserAccount("demo@finsights.local", "Demo");
    }

    @Test
    void getFallsBackToLegacyUpOnlySettingsWhenNoRowExistsYet() {
        user.setDailyThresholdPercent(new BigDecimal("5.00"));
        when(currentUser.currentUser()).thenReturn(user);
        when(repository.findByUser(user)).thenReturn(Optional.empty());

        MovementThresholdResponse response = service.get();

        assertThat(response.dailyUpPercent()).isEqualByComparingTo("5.00");
        assertThat(response.dailyDownPercent()).isNull();
    }

    @Test
    void getReadsFromItsOwnRowOnceOneExists() {
        when(currentUser.currentUser()).thenReturn(user);
        MovementThreshold row = new MovementThreshold();
        row.setUser(user);
        row.setDailyUpPercent(new BigDecimal("6.00"));
        row.setDailyDownPercent(new BigDecimal("12.00"));
        when(repository.findByUser(user)).thenReturn(Optional.of(row));

        MovementThresholdResponse response = service.get();

        assertThat(response.dailyUpPercent()).isEqualByComparingTo("6.00");
        assertThat(response.dailyDownPercent()).isEqualByComparingTo("12.00");
    }

    @Test
    void saveCreatesARowOnFirstUseAndPersistsBothDirections() {
        when(currentUser.currentUser()).thenReturn(user);
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        when(repository.save(any(MovementThreshold.class))).thenAnswer(inv -> inv.getArgument(0));
        MovementThresholdRequest request = new MovementThresholdRequest(
                new BigDecimal("5"), new BigDecimal("10"),
                null, null, null, null, null, null, null, null);

        MovementThresholdResponse response = service.save(request);

        assertThat(response.dailyUpPercent()).isEqualByComparingTo("5");
        assertThat(response.dailyDownPercent()).isEqualByComparingTo("10");
    }

    @Test
    void saveRejectsNegativeThresholds() {
        when(currentUser.currentUser()).thenReturn(user);
        when(repository.findByUser(user)).thenReturn(Optional.empty());
        MovementThresholdRequest request = new MovementThresholdRequest(
                new BigDecimal("-1"), null, null, null, null, null, null, null, null, null);

        try {
            service.save(request);
            org.junit.jupiter.api.Assertions.fail("expected ResponseStatusException");
        } catch (ResponseStatusException e) {
            assertThat(e.getStatusCode().value()).isEqualTo(400);
        }
    }
}
