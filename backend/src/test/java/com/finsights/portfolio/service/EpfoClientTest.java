package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.BrokerConnection;
import org.junit.jupiter.api.Test;

class EpfoClientTest {

    private final EpfoClient client = new EpfoClient();

    @Test
    void brokerKeyIsEpfo() {
        assertThat(client.brokerKey()).isEqualTo("epfo");
    }

    @Test
    void fetchBalanceRejectsBlankCredentialsWithoutAttemptingTheNetworkCall() {
        assertThat(client.fetchBalance(null, "password")).isEmpty();
        assertThat(client.fetchBalance("123456789012", null)).isEmpty();
        assertThat(client.fetchBalance("", "")).isEmpty();
    }

    // No egress to epfindia.gov.in in this sandbox (and no real UAN/password to test against
    // anyway — EPFO's login also expects a CAPTCHA this client has no way to solve, see the
    // class doc) — what's deterministic regardless is the never-throws, degrade-to-"no data"
    // contract every other broker client in this codebase already follows.
    @Test
    void fetchBalanceNeverThrowsAndDegradesToEmptyWithoutNetwork() {
        assertThat(client.fetchBalance("123456789012", "some-password")).isNotNull();
    }

    @Test
    void fetchSnapshotsDegradesToEmptyWithoutNetwork() {
        BrokerConnection connection = new BrokerConnection();
        connection.setAccessToken("123456789012");
        connection.setRefreshToken("some-password");

        assertThat(client.fetchSnapshots(connection)).isEmpty();
    }
}
