package com.finsights.portfolio.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.encrypt.TextEncryptor;

class BrokerTokenEncryptionTest {

    @Test
    void roundTripsThroughEncryptAndDecrypt() {
        TextEncryptor encryptor = BrokerTokenEncryption.encryptorFor("a-reasonably-long-test-secret-key");

        String ciphertext = encryptor.encrypt("super-secret-kite-access-token");

        assertThat(ciphertext).isNotEqualTo("super-secret-kite-access-token");
        assertThat(encryptor.decrypt(ciphertext)).isEqualTo("super-secret-kite-access-token");
    }

    @Test
    void sameKeyProducesDifferentCiphertextEachTime() {
        // TextEncryptor randomizes its IV per call — two encryptions of the same plaintext must
        // not be comparable/linkable by looking at stored ciphertext alone.
        TextEncryptor encryptor = BrokerTokenEncryption.encryptorFor("a-reasonably-long-test-secret-key");

        String first = encryptor.encrypt("same-value");
        String second = encryptor.encrypt("same-value");

        assertThat(first).isNotEqualTo(second);
        assertThat(encryptor.decrypt(first)).isEqualTo("same-value");
        assertThat(encryptor.decrypt(second)).isEqualTo("same-value");
    }

    @Test
    void decryptingWithADifferentKeyFails() {
        TextEncryptor a = BrokerTokenEncryption.encryptorFor("key-one-long-enough-to-use");
        TextEncryptor b = BrokerTokenEncryption.encryptorFor("key-two-long-enough-to-use");
        String ciphertext = a.encrypt("value");

        assertThatThrownBy(() -> b.decrypt(ciphertext)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void isConfiguredReflectsWhetherTheEnvVarIsSet() {
        // BROKER_TOKEN_ENCRYPTION_KEY isn't set in this test JVM — confirms the "blank is a valid,
        // supported state" contract the rest of the feature (BrokerConnectionService.kiteReady(),
        // BrokerSyncScheduler.syncAll()) relies on to no-op cleanly rather than throw.
        assertThat(BrokerTokenEncryption.isConfigured()).isFalse();
    }

    @Test
    void encryptorFromEnvThrowsClearlyWhenUnconfigured() {
        assertThatThrownBy(BrokerTokenEncryption::encryptorFromEnv)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BROKER_TOKEN_ENCRYPTION_KEY");
    }
}
