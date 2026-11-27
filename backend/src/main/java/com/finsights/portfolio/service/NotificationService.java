package com.finsights.portfolio.service;

import com.finsights.portfolio.domain.SentAlert;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.ActionItemResponse;
import com.finsights.portfolio.dto.DashboardResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import com.finsights.portfolio.dto.PortfolioTimelineResponse;
import com.finsights.portfolio.dto.TopMoverResponse;
import com.finsights.portfolio.repository.SentAlertRepository;
import com.finsights.portfolio.service.NotificationComposer.AlertLine;
import com.finsights.portfolio.service.NotificationComposer.DigestData;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Builds the signed-in user's weekly digest and daily alert email from the same services the app's
 * pages use, and remembers which alert items were already sent so none repeats.
 */
@Service
public class NotificationService implements UserDataCleanup {
    /** Action Centre kinds worth an email: money due, or something that already happened. */
    static final Set<String> ALERTABLE_KINDS = Set.of("EMI_DUE", "EMI_OVERDUE", "INTEREST_DUE", "INTEREST_OVERDUE",
            "FIXED_RATE_MATURED", "PROVIDENT_FUND_CONTRIBUTION");
    static final int DIGEST_MATURITY_DAYS = 30;
    static final int ALERT_MATURITY_DAYS = 7;
    static final Duration SENT_RETENTION = Duration.ofDays(120);

    private final CurrentUserService currentUser;
    private final DashboardService dashboard;
    private final ReturnsService returns;
    private final PortfolioSnapshotService snapshots;
    private final HoldingService holdings;
    private final ActionCentreService actionCentre;
    private final TopMoversService topMovers;
    private final SentAlertRepository sentAlerts;
    private final String appUrl;
    private final Clock clock;

    @Autowired
    public NotificationService(CurrentUserService currentUser, DashboardService dashboard, ReturnsService returns,
                               PortfolioSnapshotService snapshots, HoldingService holdings, ActionCentreService actionCentre,
                               TopMoversService topMovers, SentAlertRepository sentAlerts,
                               @Value("${app.frontend-url}") String appUrl) {
        this(currentUser, dashboard, returns, snapshots, holdings, actionCentre, topMovers, sentAlerts, appUrl,
                Clock.systemDefaultZone());
    }

    NotificationService(CurrentUserService currentUser, DashboardService dashboard, ReturnsService returns,
                        PortfolioSnapshotService snapshots, HoldingService holdings, ActionCentreService actionCentre,
                        TopMoversService topMovers, SentAlertRepository sentAlerts, String appUrl, Clock clock) {
        this.currentUser = currentUser;
        this.dashboard = dashboard;
        this.returns = returns;
        this.snapshots = snapshots;
        this.holdings = holdings;
        this.actionCentre = actionCentre;
        this.topMovers = topMovers;
        this.sentAlerts = sentAlerts;
        this.appUrl = appUrl;
        this.clock = clock;
    }

    public NotificationEmail weeklyDigest() {
        UserAccount user = currentUser.currentUser();
        String currency = currencyOf(user);
        LocalDate today = LocalDate.now(clock);
        DashboardResponse summary = dashboard.summary(currency);
        List<HoldingResponse> all = holdings.list(currency);

        List<AlertLine> attention = new ArrayList<>(attentionLines(actionCentre.actions(all, true)));
        attention.addAll(maturingLines(all, today, DIGEST_MATURITY_DAYS, currency));
        List<AlertLine> comingUp = actionCentre.upcoming().stream()
                .map(a -> new AlertLine(a.key(), a.title(), a.detail())).toList();

        return NotificationComposer.digest(new DigestData(user.getDisplayName(), currency, summary.netWorth(),
                summary.totalAssets(), summary.totalLiabilities(), weekChange(snapshots.timeline(currency)),
                returns.returns(currency).portfolio().xirrPercent(), attention, comingUp,
                moverLines(topMovers.topMovers(currency), today), appUrl));
    }

    /** Items not emailed before; empty when there's nothing new. Call {@link #markSent} once it's delivered. */
    public Optional<NotificationEmail> pendingAlert() {
        UserAccount user = currentUser.currentUser();
        String currency = currencyOf(user);
        LocalDate today = LocalDate.now(clock);
        List<HoldingResponse> all = holdings.list(currency);

        List<AlertLine> lines = new ArrayList<>(attentionLines(actionCentre.actions(all, true)));
        lines.addAll(maturingLines(all, today, ALERT_MATURITY_DAYS, currency));
        lines.addAll(moverLines(topMovers.topMovers(currency), today));

        Set<String> alreadySent = new HashSet<>(sentAlerts.findKeysByUserId(user.getId()));
        List<AlertLine> fresh = lines.stream().filter(line -> !alreadySent.contains(line.key())).toList();
        if (fresh.isEmpty()) return Optional.empty();
        return Optional.of(NotificationComposer.alert(user.getDisplayName(), fresh, appUrl));
    }

    public void markSent(String userId, List<String> keys) {
        sentAlerts.saveAll(keys.stream().map(key -> new SentAlert(userId, key)).toList());
    }

    public void pruneSent() {
        sentAlerts.deleteOlderThan(clock.instant().minus(SENT_RETENTION));
    }

    @Override
    public void deleteForUser(String userId) {
        sentAlerts.deleteByUserId(userId);
    }

    static List<AlertLine> attentionLines(List<ActionItemResponse> actions) {
        return actions.stream()
                .filter(a -> ALERTABLE_KINDS.contains(a.kind()))
                .map(a -> new AlertLine(a.key(), a.title(), a.detail()))
                .toList();
    }

    static List<AlertLine> maturingLines(List<HoldingResponse> all, LocalDate today, int withinDays, String currency) {
        LocalDate horizon = today.plusDays(withinDays);
        return all.stream()
                .filter(h -> h.valuationMethod() == ValuationMethod.FIXED_RATE)
                .filter(h -> h.fixedRateEndDate() != null && h.fixedRateEndDate().isAfter(today)
                        && !h.fixedRateEndDate().isAfter(horizon))
                .map(h -> new AlertLine("MATURING|" + h.id() + "|" + h.fixedRateEndDate(),
                        "Maturing on " + h.fixedRateEndDate() + " — " + h.name(),
                        "Currently worth " + NotificationComposer.money(h.currentValue(), currency)
                                + ". Plan where the proceeds go."))
                .toList();
    }

    /** One line per mover; the key carries the ISO week so a mover alerts at most once a week. */
    static List<AlertLine> moverLines(List<TopMoverResponse> movers, LocalDate today) {
        LocalDate week = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return movers.stream()
                .filter(m -> m.triggered() != null && !m.triggered().isEmpty())
                .map(m -> new AlertLine("MOVER|" + m.subjectType() + "|" + m.id() + "|" + week, m.name(),
                        m.triggered().stream()
                                .map(t -> t.period() + " " + NotificationComposer.signedPercent(t.percent())
                                        + " (threshold " + t.thresholdPercent().stripTrailingZeros().toPlainString() + "%)")
                                .collect(Collectors.joining(", "))))
                .toList();
    }

    /** Net worth change between the two most recent weekly snapshots, or null with fewer than two. */
    static BigDecimal weekChange(PortfolioTimelineResponse timeline) {
        List<PortfolioTimelineResponse.Week> weeks = timeline == null ? List.of() : timeline.weeks();
        if (weeks.size() < 2) return null;
        BigDecimal latest = weeks.get(weeks.size() - 1).netWorth();
        BigDecimal previous = weeks.get(weeks.size() - 2).netWorth();
        return latest.subtract(previous).setScale(2, RoundingMode.HALF_UP);
    }

    private static String currencyOf(UserAccount user) {
        String base = user.getBaseCurrency();
        if (base == null || base.isBlank()) return "INR";
        return base;
    }
}
