package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CasImportRateLimiterTest {

    private CasImportRateLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        limiter = new CasImportRateLimiter();
        Field field = CasImportRateLimiter.class.getDeclaredField("dailyLimit");
        field.setAccessible(true);
        field.set(limiter, 2);
    }

    @Test
    void allowsUpToTheDailyLimitThenBlocks() {
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isFalse();
    }

    @Test
    void tracksEachUserIndependently() {
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isFalse();

        assertThat(limiter.tryConsume("u-2")).isTrue();
    }
}
