package com.example.backend.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;

@Tag("unit")
class RefreshTokenPolicyTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 11, 3, 12, 0);

    @Test
    void rejectsRevokedAndExpiredSessions() {
        assertEquals(RefreshTokenPolicy.Decision.INVALID, RefreshTokenPolicy.decide(true,
                now.plusDays(1), now.plusDays(1), null, now));
        assertEquals(RefreshTokenPolicy.Decision.INVALID, RefreshTokenPolicy.decide(false,
                now, now.plusDays(1), null, now));
        assertEquals(RefreshTokenPolicy.Decision.INVALID, RefreshTokenPolicy.decide(false,
                now.plusDays(1), now, null, now));
    }

    @Test
    void permitsTheShortReuseGracePeriodButRejectsReuseAfterIt() {
        LocalDateTime usedAtBoundary = now.minus(RefreshTokenPolicy.REUSE_LEEWAY);
        assertEquals(RefreshTokenPolicy.Decision.ROTATE, RefreshTokenPolicy.decide(false,
                now.plusDays(1), now.plusDays(1), usedAtBoundary, now));
        assertEquals(RefreshTokenPolicy.Decision.REUSED, RefreshTokenPolicy.decide(false,
                now.plusDays(1), now.plusDays(1), usedAtBoundary.minusNanos(1), now));
    }

    @Test
    void rotatesAnUnusedAndActiveRefreshToken() {
        assertEquals(RefreshTokenPolicy.Decision.ROTATE, RefreshTokenPolicy.decide(false,
                now.plusDays(1), now.plusDays(1), null, now));
    }
}
