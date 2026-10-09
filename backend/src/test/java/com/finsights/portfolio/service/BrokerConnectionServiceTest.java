package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.finsights.portfolio.domain.BrokerConnection;
import com.finsights.portfolio.domain.UserAccount;
import com.finsights.portfolio.repository.BrokerConnectionRepository;
import com.finsights.portfolio.security.BrokerTokenEncryption;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

@ExtendWith(MockitoExtension.class)
class BrokerConnectionServiceTest {

    @Mock BrokerConnectionRepository connections;
    @Mock CurrentUserService currentUser;
    @Mock KiteConnectClient kite;
    private BrokerConnectionService service;
    private UserAccount user;

    @BeforeEach
    void setUp() {
        service = new BrokerConnectionService(connections, currentUser, kite);
        user = new UserAccount("demo@finsights.local", "Demo");
    }

    /** BROKER_TOKEN_ENCRYPTION_KEY is never set in this test JVM, so kiteReady()'s second half
     *  (BrokerTokenEncryption.isConfigured()) is real everywhere except here, where a test needs
     *  to exercise the path as if an operator had actually configured the deployment. */
    private MockedStatic<BrokerTokenEncryption> encryptionConfigured() {
        MockedStatic<BrokerTokenEncryption> mocked = mockStatic(BrokerTokenEncryption.class);
        mocked.when(BrokerTokenEncryption::isConfigured).thenReturn(true);
        return mocked;
    }

    @Test
    void onlyKiteIsConnectableInV1() {
        assertThat(service.isConnectable("kite")).isTrue();
        assertThat(service.isConnectable("groww")).isFalse();
        assertThat(service.isConnectable("indmoney")).isFalse();
        assertThat(service.isConnectable("epfo")).isFalse();
    }

    @Test
    void beginConnectRejectsAnUnsupportedBroker() {
        assertThatThrownBy(() -> service.beginConnect("groww"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unsupported");
    }

    @Test
    void beginConnectFailsClearlyWhenKiteApiCredentialsArentConfigured() {
        when(kite.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> service.beginConnect("kite"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("isn't configured");
    }

    @Test
    void beginConnectFailsClearlyWhenTheEncryptionKeyIsntConfigured() {
        // Real (unmocked) BrokerTokenEncryption.isConfigured() — false, since the test JVM never
        // sets BROKER_TOKEN_ENCRYPTION_KEY. Kite's own API credentials being fine isn't enough on
        // their own; this is the "half-configured deployment" case.
        when(kite.isConfigured()).thenReturn(true);

        assertThatThrownBy(() -> service.beginConnect("kite"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("isn't configured");
    }

    @Test
    void beginConnectReturnsKitesLoginUrlWhenFullyConfigured() {
        when(kite.isConfigured()).thenReturn(true);
        when(kite.loginUrl()).thenReturn("https://kite.zerodha.com/connect/login?v=3&api_key=x");

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            String url = service.beginConnect("kite");
            assertThat(url).isEqualTo("https://kite.zerodha.com/connect/login?v=3&api_key=x");
        }
    }

    @Test
    void completeConnectReturnsFalseWithoutCallingKiteWhenNotFullyConfigured() {
        when(kite.isConfigured()).thenReturn(false);

        boolean result = service.completeConnect("kite", "some-token");

        assertThat(result).isFalse();
        verify(kite, never()).exchangeRequestToken(any());
    }

    @Test
    void completeConnectReturnsFalseWhenKiteCantResolveTheRequestToken() {
        when(kite.isConfigured()).thenReturn(true);
        when(kite.exchangeRequestToken(any())).thenReturn(Optional.empty());

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            boolean result = service.completeConnect("kite", "bad-token");
            assertThat(result).isFalse();
        }
        verify(connections, never()).save(any());
    }

    @Test
    void completeConnectStoresANewConnectionOnSuccess() {
        when(kite.isConfigured()).thenReturn(true);
        when(kite.exchangeRequestToken("good-token"))
                .thenReturn(Optional.of(new KiteConnectClient.KiteSession("the-access-token", "KITE123")));
        when(currentUser.currentUser()).thenReturn(user);
        when(connections.findByUser_IdAndBrokerKey(user.getId(), "kite")).thenReturn(Optional.empty());

        boolean result;
        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            result = service.completeConnect("kite", "good-token");
        }

        assertThat(result).isTrue();
        var captor = org.mockito.ArgumentCaptor.forClass(BrokerConnection.class);
        verify(connections).save(captor.capture());
        assertThat(captor.getValue().getAccessToken()).isEqualTo("the-access-token");
        assertThat(captor.getValue().getBrokerKey()).isEqualTo("kite");
        assertThat(captor.getValue().getUser()).isEqualTo(user);
    }

    @Test
    void completeConnectReplacesAnExistingConnectionRatherThanDuplicatingIt() {
        when(kite.isConfigured()).thenReturn(true);
        when(kite.exchangeRequestToken("good-token"))
                .thenReturn(Optional.of(new KiteConnectClient.KiteSession("new-access-token", "KITE123")));
        when(currentUser.currentUser()).thenReturn(user);
        BrokerConnection existing = new BrokerConnection();
        existing.setAccessToken("old-access-token");
        when(connections.findByUser_IdAndBrokerKey(user.getId(), "kite")).thenReturn(Optional.of(existing));

        try (MockedStatic<BrokerTokenEncryption> ignored = encryptionConfigured()) {
            service.completeConnect("kite", "good-token");
        }

        var captor = org.mockito.ArgumentCaptor.forClass(BrokerConnection.class);
        verify(connections).save(captor.capture());
        assertThat(captor.getValue()).isSameAs(existing); // same row updated, not a second one inserted
        assertThat(captor.getValue().getAccessToken()).isEqualTo("new-access-token");
    }

    @Test
    void completeConnectRejectsAnUnsupportedBrokerWithoutTouchingKite() {
        assertThatThrownBy(() -> service.completeConnect("groww", "token"))
                .isInstanceOf(ResponseStatusException.class);
        verify(kite, never()).exchangeRequestToken(any());
    }

    @Test
    void disconnectDeletesTheConnectionButNeverTouchesHoldings() {
        when(currentUser.currentUser()).thenReturn(user);
        BrokerConnection existing = new BrokerConnection();
        when(connections.findByUser_IdAndBrokerKey(user.getId(), "kite")).thenReturn(Optional.of(existing));

        service.disconnect("kite");

        verify(connections).delete(existing);
    }

    @Test
    void disconnectIsANoOpWhenNothingIsConnected() {
        when(currentUser.currentUser()).thenReturn(user);
        when(connections.findByUser_IdAndBrokerKey(any(), any())).thenReturn(Optional.empty());

        service.disconnect("kite");

        verify(connections, never()).delete(any(BrokerConnection.class));
    }

    @Test
    void statusForIsEmptyForABrokerThatIsntConnectable() {
        assertThat(service.statusFor("user-1", "groww")).isEmpty();
        verify(connections, never()).findByUser_IdAndBrokerKey(any(), any());
    }

    @Test
    void statusForFlagsAConnectionPastItsExpiryAsNeedingReauth() {
        BrokerConnection expired = new BrokerConnection();
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        when(connections.findByUser_IdAndBrokerKey("user-1", "kite")).thenReturn(Optional.of(expired));

        var status = service.statusFor("user-1", "kite");

        assertThat(status).isPresent();
        assertThat(status.get().needsReauth()).isTrue();
    }

    @Test
    void statusForDoesNotFlagAConnectionStillWithinItsExpiry() {
        BrokerConnection fresh = new BrokerConnection();
        fresh.setExpiresAt(Instant.now().plus(1, ChronoUnit.HOURS));
        when(connections.findByUser_IdAndBrokerKey("user-1", "kite")).thenReturn(Optional.of(fresh));

        var status = service.statusFor("user-1", "kite");

        assertThat(status).isPresent();
        assertThat(status.get().needsReauth()).isFalse();
    }
}
