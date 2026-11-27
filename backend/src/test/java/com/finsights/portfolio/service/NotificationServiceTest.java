package com.finsights.portfolio.service;

import static com.finsights.portfolio.service.TestHoldings.holding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.SentAlert;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.DashboardResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.dto.ReturnsResponse;
import com.finsights.portfolio.dto.TopMoverResponse;
import com.finsights.portfolio.repository.SentAlertRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class NotificationServiceTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 26);   // a Thursday
    private static final Clock CLOCK = Clock.fixed(TODAY.atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);

    @Mock CurrentUserService currentUser;
    @Mock DashboardService dashboard;
    @Mock ReturnsService returns;
    @Mock PortfolioSnapshotService snapshots;
    @Mock HoldingService holdings;
    @Mock ActionCentreService actionCentre;
    @Mock TopMoversService topMovers;
    @Mock SentAlertRepository sentAlerts;
    private NotificationService service;
    private UserAccount user;

    private final ActionItemResponse emiDue = action("EMI_DUE", "EMI due — Home loan");
    private final ActionItemResponse noBroker = action("DQ_NO_BROKER", "No broker — Gold");
    private final TopMoverResponse mover = new TopMoverResponse("HOLDING", "h-9", "Reliance", "Equity", "RELIANCE.NS",
            BigDecimal.TEN, "INR", List.of(new TopMoverResponse.PeriodMovement("DAILY", new BigDecimal("6.2"), new BigDecimal("5.00"))));
    private final TopMoverResponse quiet = new TopMoverResponse("WATCHLIST", "w-1", "Nifty", null, "^NSEI", null, null, List.of());

    @BeforeEach
    void setUp() {
        service = new NotificationService(currentUser, dashboard, returns, snapshots, holdings, actionCentre, topMovers,
                sentAlerts, "https://app.example", CLOCK);
        user = new UserAccount("asha@example.com", "Asha");
        ReflectionTestUtils.setField(user, "id", "u-1");
        lenient().when(currentUser.currentUser()).thenReturn(user);
        lenient().when(holdings.list("INR")).thenReturn(List.of(
                holding("fd-soon").name("SBI FD").method(ValuationMethod.FIXED_RATE).current("100000").maturesOn(TODAY.plusDays(5)).build(),
                holding("fd-later").name("HDFC FD").method(ValuationMethod.FIXED_RATE).maturesOn(TODAY.plusDays(20)).build(),
                holding("fd-past").method(ValuationMethod.FIXED_RATE).maturesOn(TODAY).build(),
                holding("fd-open").method(ValuationMethod.FIXED_RATE).build(),
                holding("stock").method(ValuationMethod.MARKET_PRICE).maturesOn(TODAY.plusDays(3)).build()));
        lenient().when(actionCentre.actions(anyList(), org.mockito.ArgumentMatchers.eq(true))).thenReturn(List.of(emiDue, noBroker));
        lenient().when(topMovers.topMovers("INR")).thenReturn(List.of(mover, quiet));
    }

    @Test
    void digestCombinesTotalsReturnsTasksMaturitiesUpcomingAndMovers() {
        when(dashboard.summary("INR")).thenReturn(new DashboardResponse(new BigDecimal("900"), new BigDecimal("1000"),
                new BigDecimal("100"), BigDecimal.ZERO, BigDecimal.ZERO, List.of(), List.of(), List.of()));
        when(snapshots.timeline("INR")).thenReturn(timeline("800", "900"));
        when(returns.returns("INR")).thenReturn(new ReturnsResponse("INR", TODAY,
                new ReturnsResponse.Figure(new BigDecimal("9.5"), null, null, null, null, null), List.of(), List.of()));
        when(actionCentre.upcoming()).thenReturn(List.of(action("INTEREST_DUE", "Interest payout — PPF")));

        NotificationEmail email = service.weeklyDigest();

        assertThat(email.subject()).isEqualTo("Your FinSights week: net worth ₹900 (+₹100)");
        assertThat(email.body())
                .contains("Portfolio XIRR: +9.50% a year")
                .contains("Needs attention (3):")
                .contains("EMI due — Home loan")
                .doesNotContain("No broker")
                .contains("Maturing on " + TODAY.plusDays(5) + " — SBI FD")
                .contains("Maturing on " + TODAY.plusDays(20) + " — HDFC FD")
                .contains("Coming up (1):\n• Interest payout — PPF")
                .contains("Moved past your thresholds (1):\n• Reliance\n  DAILY +6.20% (threshold 5%)")
                .doesNotContain("Nifty");
    }

    @Test
    void alertSkipsAlreadySentItemsAndUsesTheShorterMaturityWindow() {
        when(sentAlerts.findKeysByUserId("u-1")).thenReturn(List.of(emiDue.key()));

        NotificationEmail email = service.pendingAlert().orElseThrow();

        assertThat(email.alertKeys()).containsExactly(
                "MATURING|fd-soon|" + TODAY.plusDays(5),
                "MOVER|HOLDING|h-9|2026-11-23");
        assertThat(email.body()).contains("Currently worth ₹1,00,000").doesNotContain("HDFC FD");
    }

    @Test
    void nothingNewMeansNoAlert() {
        when(sentAlerts.findKeysByUserId("u-1")).thenReturn(List.of(emiDue.key(),
                "MATURING|fd-soon|" + TODAY.plusDays(5), "MOVER|HOLDING|h-9|2026-11-23"));

        assertThat(service.pendingAlert()).isEmpty();
    }

    @Test
    void blankBaseCurrencyFallsBackToInr() {
        user.setBaseCurrency("");
        when(sentAlerts.findKeysByUserId("u-1")).thenReturn(List.of());
        assertThat(service.pendingAlert()).isPresent();
    }

    @SuppressWarnings("unchecked")
    @Test
    void marksPrunesAndDeletesSentAlerts() {
        service.markSent("u-1", List.of("a", "b"));
        ArgumentCaptor<List<SentAlert>> saved = ArgumentCaptor.forClass(List.class);
        verify(sentAlerts).saveAll(saved.capture());
        assertThat(saved.getValue()).extracting(SentAlert::getAlertKey).containsExactly("a", "b");
        assertThat(saved.getValue()).extracting(SentAlert::getUserId).containsOnly("u-1");

        service.pruneSent();
        verify(sentAlerts).deleteOlderThan(CLOCK.instant().minus(NotificationService.SENT_RETENTION));

        service.deleteForUser("u-1");
        verify(sentAlerts).deleteByUserId("u-1");
    }

    @Test
    void weekChangeNeedsTwoSnapshots() {
        assertThat(NotificationService.weekChange(null)).isNull();
        assertThat(NotificationService.weekChange(timeline("5"))).isNull();
        assertThat(NotificationService.weekChange(timeline("10", "4"))).isEqualByComparingTo("-6");
    }

    @Test
    void moversWithoutTriggerListAreSkipped() {
        TopMoverResponse noList = new TopMoverResponse("HOLDING", "x", "X", null, null, null, null, null);
        assertThat(NotificationService.moverLines(List.of(noList), TODAY)).isEmpty();
    }

    private static PortfolioTimelineResponse timeline(String... netWorths) {
        List<PortfolioTimelineResponse.Week> weeks = java.util.Arrays.stream(netWorths)
                .map(v -> new PortfolioTimelineResponse.Week(TODAY, null, null, null, new BigDecimal(v), List.of()))
                .toList();
        return new PortfolioTimelineResponse(weeks, null, false);
    }

    private static ActionItemResponse action(String kind, String title) {
        return new ActionItemResponse(kind, "WARN", title, "detail", "h", "n", null, null, null, null, null,
                ActionItemResponse.keyOf(kind, "h", null));
    }
}
