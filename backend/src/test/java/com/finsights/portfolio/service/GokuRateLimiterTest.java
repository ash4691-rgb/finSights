package com.finsights.portfolio.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class GokuRateLimiterTest {

    private GokuRateLimiter limiter;

    @BeforeEach
    void setUp() throws Exception {
        limiter = new GokuRateLimiter();
        Field field = GokuRateLimiter.class.getDeclaredField("dailyLimit");
        field.setAccessible(true);
        field.set(limiter, 3);
    }

    @Test
    void allowsUpToTheDailyLimitThenBlocks() {
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isFalse();
    }

    @Test
    void tracksEachUserIndependently() {
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isTrue();
        assertThat(limiter.tryConsume("u-1")).isFalse();

        assertThat(limiter.tryConsume("u-2")).isTrue();
    }

    @Test
    void remainingTodayReflectsWhatsBeenConsumed() {
        assertThat(limiter.remainingToday("u-1")).isEqualTo(3);
        limiter.tryConsume("u-1");
        assertThat(limiter.remainingToday("u-1")).isEqualTo(2);
        limiter.tryConsume("u-1");
        limiter.tryConsume("u-1");
        limiter.tryConsume("u-1"); // one over the cap
        assertThat(limiter.remainingToday("u-1")).isEqualTo(0);
    }
}
