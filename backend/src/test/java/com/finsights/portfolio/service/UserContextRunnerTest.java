package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class UserContextRunnerTest {
    private final UserContextRunner runner = new UserContextRunner();

    @AfterEach
    void clear() { SecurityContextHolder.clearContext(); }

    @Test
    void runsAsTheUserAndRestoresThePreviousContext() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("scheduler", null));

        String seen = runner.runAs("asha@example.com", () -> {
            var auth = SecurityContextHolder.getContext().getAuthentication();
            assertThat(auth.isAuthenticated()).isTrue();
            return auth.getName();
        });

        assertThat(seen).isEqualTo("asha@example.com");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("scheduler");
    }

    @Test
    void restoresTheContextEvenWhenTheWorkThrows() {
        assertThatThrownBy(() -> runner.runAs("asha@example.com", () -> { throw new IllegalStateException("boom"); }))
                .hasMessage("boom");
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
    }
}
