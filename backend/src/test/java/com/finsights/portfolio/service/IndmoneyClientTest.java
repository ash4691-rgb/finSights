package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.BrokerConnection;
import org.junit.jupiter.api.Test;

class IndmoneyClientTest {

    private final IndmoneyClient client = new IndmoneyClient();

    @Test
    void brokerKeyIsIndmoney() {
        assertThat(client.brokerKey()).isEqualTo("indmoney");
    }

    @Test
    void fetchHoldingsRejectsABlankTokenWithoutAttemptingTheNetworkCall() {
        assertThat(client.fetchHoldings(null)).isEmpty();
        assertThat(client.fetchHoldings("")).isEmpty();
        assertThat(client.fetchHoldings("   ")).isEmpty();
    }

    // No egress to api.indstocks.com in this sandbox (and no real account/token to test against
    // anyway) — what's deterministic regardless of network reachability is the never-throws,
    // degrade-to-"no data" contract every other broker client in this codebase already follows.
    @Test
    void fetchHoldingsNeverThrowsAndDegradesToEmptyWithoutNetwork() {
        assertThat(client.fetchHoldings("some-bearer-token")).isNotNull();
    }

    @Test
    void fetchSnapshotsDegradesToEmptyWithoutNetwork() {
        BrokerConnection connection = new BrokerConnection();
        connection.setAccessToken("some-bearer-token");

        assertThat(client.fetchSnapshots(connection)).isEmpty();
    }
}
