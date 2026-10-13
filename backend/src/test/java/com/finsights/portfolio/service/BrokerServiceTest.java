package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.HoldingKind;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.domain.ValuationMethod;
import com.finsights.portfolio.dto.BrokersResponse;
import com.finsights.portfolio.dto.HoldingResponse;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class BrokerServiceTest {

    @Mock HoldingService holdings;
    @Mock BrokerConnectionService connections;
    @Mock CurrentUserService currentUser;
    private BrokerService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new BrokerService(holdings, connections, currentUser);
        user = new UserAccount("demo@finsights.local", "Demo");
        when(holdings.list(any())).thenReturn(List.of());
        when(currentUser.currentUser()).thenReturn(user);
    }

    private BrokersResponse.Source kiteSource(BrokersResponse response) {
        return response.sources().stream().filter(s -> s.key().equals("kite")).findFirst().orElseThrow();
    }

    private HoldingResponse holdingOf(String id, String broker, String invested, String current) {
        return new HoldingResponse(id, "ref-" + id, "c-1", "Category", "Holding " + id, HoldingKind.ASSET, ValuationMethod.MANUAL,
                null, broker, "INR", "INR", new BigDecimal(invested), new BigDecimal(current),
                new BigDecimal(current).subtract(new BigDecimal(invested)), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
                null, null, null, null, null, null, null, null, null, null,
                false, false, null, null, java.util.Set.of(), null, null, null, false, null);
    }

    @Test
    void computesProfitLossPercentageFromInvestedAndCurrentValue() {
        when(holdings.list((String) null)).thenReturn(List.of(
                holdingOf("h-1", "Zerodha", "100000", "135000"),
                holdingOf("h-2", "Zerodha", "80000", "100000")));
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        BrokersResponse.BrokerGroup zerodha = response.brokers().stream().filter(b -> b.name().equals("Zerodha")).findFirst().orElseThrow();
        // invested 180000, current 235000, profitLoss 55000 -> 55000/180000 rounds to 0.3056 at
        // scale 4 before the *100, giving 30.5600 (not the unrounded 30.5555...).
        assertThat(zerodha.profitLoss()).isEqualByComparingTo("55000");
        assertThat(zerodha.profitLossPercentage()).isEqualByComparingTo(new BigDecimal("30.5600"));
    }

    @Test
    void profitLossPercentageIsZeroWhenNothingIsInvested() {
        when(holdings.list((String) null)).thenReturn(List.of(holdingOf("h-1", "Zerodha", "0", "0")));
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        BrokersResponse.BrokerGroup zerodha = response.brokers().stream().filter(b -> b.name().equals("Zerodha")).findFirst().orElseThrow();
        assertThat(zerodha.profitLossPercentage()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void kiteIsComingSoonWhenNothingIsConnected() {
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        BrokersResponse.Source kite = kiteSource(response);
        assertThat(kite.status()).isEqualTo("COMING_SOON");
        assertThat(kite.connected()).isFalse();
    }

    @Test
    void kiteShowsAsConnectedWithItsLastSyncTimeOnceLinked() {
        Instant syncedAt = Instant.now();
        when(connections.statusFor(user.getId(), "kite"))
                .thenReturn(Optional.of(new BrokerConnectionService.ConnectionStatus(syncedAt, false, null)));

        BrokersResponse response = service.overview(null);

        BrokersResponse.Source kite = kiteSource(response);
        assertThat(kite.status()).isEqualTo("CONNECTED");
        assertThat(kite.connected()).isTrue();
        assertThat(kite.lastSyncedAt()).isEqualTo(syncedAt);
        assertThat(kite.needsReauth()).isFalse();
    }

    @Test
    void kiteFlagsNeedsReauthWhenTheConnectionHasExpired() {
        when(connections.statusFor(user.getId(), "kite"))
                .thenReturn(Optional.of(new BrokerConnectionService.ConnectionStatus(Instant.now(), true, "token expired")));

        BrokersResponse response = service.overview(null);

        BrokersResponse.Source kite = kiteSource(response);
        assertThat(kite.status()).isEqualTo("NEEDS_REAUTH");
        assertThat(kite.needsReauth()).isTrue();
    }

    @Test
    void sourcesWithoutAConnectFlowAreNeverQueriedForStatus() {
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        BrokersResponse.Source groww = response.sources().stream().filter(s -> s.key().equals("groww")).findFirst().orElseThrow();
        assertThat(groww.status()).isEqualTo("PLANNED");
        assertThat(groww.connectable()).isFalse();
        assertThat(groww.connected()).isFalse();
        assertThat(groww.connectFlow()).isNull();
        org.mockito.Mockito.verify(connections, org.mockito.Mockito.never()).statusFor(user.getId(), "groww");
    }

    @Test
    void kiteUsesTheRedirectConnectFlow() {
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "coinswitch")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "indmoney")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "epfo")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        assertThat(kiteSource(response).connectFlow()).isEqualTo("REDIRECT");
    }

    @Test
    void epfoIsConnectableViaTheUanPasswordFlow() {
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "coinswitch")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "indmoney")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "epfo")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        BrokersResponse.Source epfo = response.sources().stream().filter(s -> s.key().equals("epfo")).findFirst().orElseThrow();
        assertThat(epfo.connectFlow()).isEqualTo("UAN_PASSWORD");
        assertThat(epfo.connectable()).isTrue();
    }

    @Test
    void coinswitchAndIndmoneyAreConnectableViaThePasteKeyFlow() {
        when(connections.statusFor(user.getId(), "kite")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "coinswitch"))
                .thenReturn(Optional.of(new BrokerConnectionService.ConnectionStatus(Instant.now(), false, null)));
        when(connections.statusFor(user.getId(), "indmoney")).thenReturn(Optional.empty());
        when(connections.statusFor(user.getId(), "epfo")).thenReturn(Optional.empty());

        BrokersResponse response = service.overview(null);

        BrokersResponse.Source coinswitch = response.sources().stream().filter(s -> s.key().equals("coinswitch")).findFirst().orElseThrow();
        BrokersResponse.Source indmoney = response.sources().stream().filter(s -> s.key().equals("indmoney")).findFirst().orElseThrow();
        assertThat(coinswitch.connectFlow()).isEqualTo("API_KEY_SECRET");
        assertThat(coinswitch.status()).isEqualTo("CONNECTED");
        assertThat(indmoney.connectFlow()).isEqualTo("API_KEY_ONLY");
        assertThat(indmoney.status()).isEqualTo("COMING_SOON");
    }
}
