package com.finsights.portfolio.security;

import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.security.crypto.encrypt.TextEncryptor;

/**
 * The one place {@code BROKER_TOKEN_ENCRYPTION_KEY} is read and turned into a usable encryptor
 * for {@link BrokerTokenConverter} — kept separate from the converter itself (a plain JPA
 * {@code AttributeConverter}, not reliably Spring-managed without extra Hibernate/Spring glue)
 * so this logic is unit-testable directly, without an env var or a persistence context.
 *
 * <p>Deliberately a plain env-var read via {@link System#getenv}, not {@code @Value} — the app's
 * other secrets (Google OAuth, Resend, Anthropic) are all read inside ordinary {@code @Service}
 * beans, always Spring-managed; a JPA converter isn't guaranteed to be without relying on Spring
 * Boot's Hibernate {@code SpringBeanContainer} wiring, which this avoids needing at all.
 */
public final class BrokerTokenEncryption {

    // Not secret — see Encryptors.text's contract: only the key/password needs to be. A salt's
    // job is to defeat rainbow-table attacks on a weak, guessable password; a long random
    // BROKER_TOKEN_ENCRYPTION_KEY pulled from env isn't that, so a fixed, non-secret salt here
    // (standard for Spring Security Crypto's TextEncryptor) doesn't weaken anything.
    private static final String SALT = "a6c3f0b1d8e4f29c";
    static final String ENV_VAR = "BROKER_TOKEN_ENCRYPTION_KEY";

    private BrokerTokenEncryption() { }

    public static boolean isConfigured() {
        String key = System.getenv(ENV_VAR);
        return key != null && !key.isBlank();
    }

    /** @throws IllegalStateException if {@code BROKER_TOKEN_ENCRYPTION_KEY} isn't set — callers
     *  that can run before any BrokerConnection exists (i.e. the whole app, most of the time)
     *  should check {@link #isConfigured()} first rather than let this throw. */
    public static TextEncryptor encryptorFromEnv() {
        String key = System.getenv(ENV_VAR);
        if (key == null || key.isBlank()) {
            throw new IllegalStateException(ENV_VAR + " is not configured — cannot store or read broker credentials");
        }
        return encryptorFor(key);
    }

    public static TextEncryptor encryptorFor(String key) {
        return Encryptors.text(key, SALT);
    }
}
