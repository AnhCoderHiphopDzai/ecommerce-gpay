package com.example.backend.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class AuthRateLimiterTest {

    @Test
    void incrementsAndResetsAnIndividualKey() {
        AuthRateLimiter limiter = new AuthRateLimiter(10);

        assertEquals(1, limiter.increment("login:127.0.0.1", Duration.ofMinutes(1)));
        assertEquals(2, limiter.increment("login:127.0.0.1", Duration.ofMinutes(1)));
        assertEquals(2, limiter.current("login:127.0.0.1"));

        limiter.reset("login:127.0.0.1");
        assertEquals(0, limiter.current("login:127.0.0.1"));
    }

    @Test
    void limitsTrackedKeysWhenTheMapIsAtCapacity() {
        AuthRateLimiter limiter = new AuthRateLimiter(1);
        limiter.increment("first", Duration.ofMinutes(1));

        assertEquals(1, limiter.increment("second", Duration.ofMinutes(1)));
        assertEquals(1, limiter.size());
    }
}
