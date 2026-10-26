package com.example.backend.security;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * Quy tắc quyết định khi trình duyệt đưa refresh token lên. Viết thành hàm thuần để dễ kiểm thử.
 *
 * - INVALID: phiên đã bị thu hồi, hoặc phiên/token đã hết hạn  -> 401
 * - REUSED : token ĐÃ DÙNG rồi mà bị đưa lên lại sau thời gian ân hạn -> nghi bị đánh cắp, thu hồi cả phiên
 * - ROTATE : hợp lệ -> đổi sang refresh token mới
 */
public final class RefreshTokenPolicy {

    public enum Decision { INVALID, REUSED, ROTATE }

    /**
     * Ân hạn: hai tab mở cùng lúc hoặc mạng chậm làm cùng một token bị gửi hai lần trong vài giây.
     * Trong khoảng này coi là bình thường, không phải bị đánh cắp.
     */
    public static final Duration REUSE_LEEWAY = Duration.ofSeconds(10);

    private RefreshTokenPolicy() {
    }

    public static Decision decide(boolean sessionRevoked,
                                  LocalDateTime sessionExpiresAt,
                                  LocalDateTime tokenExpiresAt,
                                  LocalDateTime tokenUsedAt,
                                  LocalDateTime now) {
        if (sessionRevoked) return Decision.INVALID;
        if (!sessionExpiresAt.isAfter(now) || !tokenExpiresAt.isAfter(now)) return Decision.INVALID;
        if (tokenUsedAt != null && tokenUsedAt.plus(REUSE_LEEWAY).isBefore(now)) return Decision.REUSED;
        return Decision.ROTATE;
    }
}
