package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.finsights.portfolio.domain.BrokerConnection;
import org.junit.jupiter.api.Test;

class CoinSwitchClientTest {

    // Any 32-byte (64 hex char) value is a valid Ed25519 seed — there's no "invalid key" concept
    // to defend against here, only "a key CoinSwitch itself would reject," which only a live call
    // could tell us.
    private static final String TEST_SEED_HEX = "ab".repeat(32);

    private final CoinSwitchClient client = new CoinSwitchClient();

    @Test
    void brokerKeyIsCoinswitch() {
        assertThat(client.brokerKey()).isEqualTo("coinswitch");
    }

    @Test
    void fetchPortfolioRejectsBlankCredentialsWithoutAttemptingTheNetworkCall() {
        assertThat(client.fetchPortfolio(null, TEST_SEED_HEX)).isEmpty();
        assertThat(client.fetchPortfolio("key", null)).isEmpty();
        assertThat(client.fetchPortfolio("", "")).isEmpty();
    }

    // No egress to coinswitch.co in this sandbox (and no real account to test against anyway) —
    // what's deterministic regardless of network reachability is the never-throws, degrade-to-
    // "no data" contract every other broker client in this codebase already follows.
    @Test
    void fetchPortfolioNeverThrowsAndDegradesToEmptyWithoutNetwork() {
        assertThat(client.fetchPortfolio("some-api-key", TEST_SEED_HEX)).isNotNull();
    }

    @Test
    void fetchSnapshotsDegradesToEmptyWithoutNetwork() {
        BrokerConnection connection = new BrokerConnection();
        connection.setAccessToken("some-api-key");
        connection.setRefreshToken(TEST_SEED_HEX);

        assertThat(client.fetchSnapshots(connection)).isEmpty();
    }

    // The never-throws test above would pass identically whether the Ed25519 signing code is
    // correct OR completely broken (both degrade to Optional.empty() once the network call fails)
    // — these two tests exercise the signing math directly, independent of network, so a broken
    // key/signature implementation can't hide behind "no egress in this sandbox."
    @Test
    void signProducesADeterministic128CharHexSignature() throws Exception {
        String first = CoinSwitchClient.sign(TEST_SEED_HEX, "GET/trade/api/v2/user/portfolio1700000000000");
        String second = CoinSwitchClient.sign(TEST_SEED_HEX, "GET/trade/api/v2/user/portfolio1700000000000");

        assertThat(first).hasSize(128); // Ed25519 signature is 64 bytes -> 128 hex chars
        assertThat(first).isEqualTo(second); // Ed25519 signing is deterministic, not randomized
    }

    @Test
    void signProducesADifferentSignatureForADifferentMessage() throws Exception {
        String first = CoinSwitchClient.sign(TEST_SEED_HEX, "GET/trade/api/v2/user/portfolio1700000000000");
        String second = CoinSwitchClient.sign(TEST_SEED_HEX, "GET/trade/api/v2/user/portfolio1700000000001");

        assertThat(first).isNotEqualTo(second);
    }

    @Test
    void signProducesADifferentSignatureForADifferentKey() throws Exception {
        String first = CoinSwitchClient.sign(TEST_SEED_HEX, "GET/trade/api/v2/user/portfolio1700000000000");
        String second = CoinSwitchClient.sign("cd".repeat(32), "GET/trade/api/v2/user/portfolio1700000000000");

        assertThat(first).isNotEqualTo(second);
    }
}
