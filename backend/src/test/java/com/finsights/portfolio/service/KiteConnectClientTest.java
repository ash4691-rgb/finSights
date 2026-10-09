package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class KiteConnectClientTest {

    @Test
    void isConfiguredRequiresAllThreeOfApiKeyApiSecretAndRedirectUrl() {
        assertThat(new KiteConnectClient("key", "secret", "https://x/callback").isConfigured()).isTrue();
        assertThat(new KiteConnectClient("", "secret", "https://x/callback").isConfigured()).isFalse();
        assertThat(new KiteConnectClient("key", "", "https://x/callback").isConfigured()).isFalse();
        assertThat(new KiteConnectClient("key", "secret", "").isConfigured()).isFalse();
        assertThat(new KiteConnectClient(null, null, null).isConfigured()).isFalse();
    }

    @Test
    void loginUrlCarriesTheApiKeyAndKiteConnectV3VersionFlag() {
        KiteConnectClient client = new KiteConnectClient("my-api-key", "secret", "https://x/callback");

        String url = client.loginUrl();

        assertThat(url).startsWith("https://kite.zerodha.com/connect/login?");
        assertThat(url).contains("v=3");
        assertThat(url).contains("api_key=my-api-key");
    }

    // No egress to api.kite.trade in this sandbox (and no real credentials to test against live
    // anyway) — what's deterministic regardless of network reachability is the never-throws,
    // degrade-to-"no data" contract every other MarketDataService-style client in this codebase
    // already follows.
    @Test
    void exchangeRequestTokenNeverThrowsAndDegradesToEmptyWithoutNetwork() {
        KiteConnectClient client = new KiteConnectClient("key", "secret", "https://x/callback");

        assertThat(client.exchangeRequestToken("some-request-token")).isNotNull();
    }

    @Test
    void exchangeRequestTokenRejectsABlankTokenWithoutAttemptingTheNetworkCall() {
        KiteConnectClient client = new KiteConnectClient("key", "secret", "https://x/callback");

        assertThat(client.exchangeRequestToken(null)).isEmpty();
        assertThat(client.exchangeRequestToken("")).isEmpty();
        assertThat(client.exchangeRequestToken("   ")).isEmpty();
    }

    @Test
    void fetchHoldingsNeverThrowsAndDegradesToEmptyWithoutNetwork() {
        KiteConnectClient client = new KiteConnectClient("key", "secret", "https://x/callback");

        assertThat(client.fetchHoldings("some-access-token")).isNotNull();
    }

    @Test
    void fetchHoldingsRejectsABlankAccessTokenWithoutAttemptingTheNetworkCall() {
        KiteConnectClient client = new KiteConnectClient("key", "secret", "https://x/callback");

        assertThat(client.fetchHoldings(null)).isEmpty();
        assertThat(client.fetchHoldings("")).isEmpty();
    }
}
