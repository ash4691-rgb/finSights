package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.ManualType;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.ManualDetails;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ActionCentreServiceTest {
    private final EmiService emis = mock(EmiService.class);
    private final InterestPayoutService interestPayouts = mock(InterestPayoutService.class);
    private final ProvidentFundContributionService providentFund = mock(ProvidentFundContributionService.class);
    private final PendingBrokerHoldingService pending = mock(PendingBrokerHoldingService.class);
    private final ActionDismissalService dismissals = mock(ActionDismissalService.class);
    private final CurrentUserService currentUser = mock(CurrentUserService.class);
    private final ActionCentreService service = new ActionCentreService(emis, interestPayouts, providentFund, pending, dismissals, currentUser);

    @BeforeEach
    void setUp() {
        UserAccount user = new UserAccount("demo@finsights.local", "Demo");
        when(currentUser.currentUser()).thenReturn(user);
        when(emis.dueItems()).thenReturn(List.of());
        when(interestPayouts.dueItems()).thenReturn(List.of());
        when(pending.actionItems()).thenReturn(List.of());
        when(providentFund.dueItems(any())).thenReturn(List.of());
        when(dismissals.suppressedKeys()).thenReturn(Set.of());
    }

    private HoldingResponse holding(ManualType type) {
        return holding(type, ManualDetails.NONE);
    }

    private HoldingResponse holding(ManualType type, ManualDetails manual) {
        return holding(type, manual, HoldingKind.ASSET, ValuationMethod.MANUAL);
    }

    private HoldingResponse holding(ManualType type, ManualDetails manual, HoldingKind kind, ValuationMethod method) {
        return new HoldingResponse("h-1", "epf-1", "c-1", "Provident Fund", "EPF - Acme", kind, method,
                null, "EPFO", user().getBaseCurrency(), user().getBaseCurrency(), new BigDecimal("148000"), new BigDecimal("160000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false, null, null, Set.of(), Instant.now(), Instant.now(), null, false, null,
                type, manual);
    }

    private UserAccount user() {
        return currentUser.currentUser();
    }

    @Test
    void manualHoldingGetsAYearlyValuationReviewDueEndOfOctober() {
        List<ActionItemResponse> items = service.actions(List.of(holding(ManualType.PROVIDENT_FUND)), true, LocalDate.of(2026, 10, 3));

        assertThat(items).hasSize(1);
        ActionItemResponse check = items.get(0);
        assertThat(check.kind()).isEqualTo("MANUAL_VALUATION_REVIEW");
        assertThat(check.severity()).isEqualTo("WARN");
        assertThat(check.dueDate()).isEqualTo(LocalDate.of(2026, 10, 31));
        assertThat(check.period()).isEqualTo("2026");
        assertThat(check.key()).isEqualTo("MANUAL_VALUATION_REVIEW|h-1|2026");
        assertThat(check.title()).contains("EPF - Acme");
    }

    @Test
    void theCheckStaysUntilYearEndAndRepeatsWithANewKeyNextYear() {
        assertThat(service.actions(List.of(holding(ManualType.PROVIDENT_FUND)), true, LocalDate.of(2026, 12, 20))).hasSize(1);
        List<ActionItemResponse> nextYear = service.actions(List.of(holding(ManualType.PROVIDENT_FUND)), true, LocalDate.of(2027, 10, 1));

        assertThat(nextYear.get(0).key()).isEqualTo("MANUAL_VALUATION_REVIEW|h-1|2027");
    }

    @Test
    void noCheckBeforeOctober() {
        assertThat(service.actions(List.of(holding(ManualType.PROVIDENT_FUND)), true, LocalDate.of(2026, 9, 30))).isEmpty();
    }

    @Test
    void noReviewForNonManualOrLiabilityHoldings() {
        LocalDate day = LocalDate.of(2026, 10, 15);
        HoldingResponse liability = holding(ManualType.DEFAULT, ManualDetails.NONE, HoldingKind.LIABILITY, ValuationMethod.MANUAL);
        HoldingResponse fixedRate = holding(ManualType.DEFAULT, ManualDetails.NONE, HoldingKind.ASSET, ValuationMethod.FIXED_RATE);

        assertThat(service.actions(List.of(liability, fixedRate), true, day)).extracting(ActionItemResponse::kind)
                .doesNotContain("MANUAL_VALUATION_REVIEW");
    }

    @Test
    void everyManualAssetTypeGetsTheReview() {
        LocalDate day = LocalDate.of(2026, 10, 15);

        assertThat(service.actions(List.of(holding(ManualType.DEFAULT)), true, day)).hasSize(1);
        assertThat(service.actions(List.of(holding(ManualType.REAL_ESTATE)), true, day)).hasSize(1);
    }

    @Test
    void aDismissedCheckIsHiddenForThatYearOnly() {
        when(dismissals.suppressedKeys()).thenReturn(Set.of("MANUAL_VALUATION_REVIEW|h-1|2026"));

        assertThat(service.actions(List.of(holding(ManualType.PROVIDENT_FUND)), true, LocalDate.of(2026, 10, 15))).isEmpty();
        assertThat(service.actions(List.of(holding(ManualType.PROVIDENT_FUND)), true, LocalDate.of(2027, 10, 15))).hasSize(1);
    }

    @Test
    void contributionTasksFromTheProvidentFundServiceAreIncluded() {
        ActionItemResponse task = new ActionItemResponse("PROVIDENT_FUND_CONTRIBUTION", "INFO", "Confirm", "detail", "h-9", "PF",
                "EPFO", LocalDate.of(2026, 3, 31), BigDecimal.TEN, "2026-02", null, "PROVIDENT_FUND_CONTRIBUTION|h-9|2026-02");
        when(providentFund.dueItems(LocalDate.of(2026, 3, 5))).thenReturn(List.of(task));

        assertThat(service.actions(List.of(), true, LocalDate.of(2026, 3, 5))).containsExactly(task);
    }

    @Test
    void theDefaultEntryPointUsesToday() {
        assertThat(service.actions(List.of(), true)).isEmpty();
    }
}
