package com.example.backend.service;

import com.example.backend.dao.RefreshTokenRepository;
import com.example.backend.dao.UserRepository;
import com.example.backend.dao.UserSessionRepository;
import com.example.backend.model.RefreshToken;
import com.example.backend.model.User;
import com.example.backend.model.UserSession;
import com.example.backend.security.AppUserDetails;
import com.example.backend.security.ClientInfo;
import com.example.backend.security.RefreshTokenPolicy;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * Quản lý phiên đăng nhập và refresh token: tạo phiên, đổi (xoay vòng) token, phát hiện token bị dùng lại,
 * thu hồi phiên, dọn dữ liệu cũ.
 */
@Service
@RequiredArgsConstructor
public class SessionService {

    // Nhật ký an ninh: nên chuyển về hệ thống giám sát tập trung (ELK, Splunk...) khi chạy thật
    private static final Logger AUDIT = LoggerFactory.getLogger("security.audit");
    private static final SecureRandom RANDOM = new SecureRandom();

    public enum Status { OK, INVALID, REUSED }

    public record NewSession(String sessionId, String refreshToken, Duration refreshTtl) {
    }

    public record Rotation(Status status, AppUserDetails user, String sessionId,
                           String refreshToken, Duration refreshTtl) {
        static Rotation failed(Status status) {
            return new Rotation(status, null, null, null, null);
        }
    }

    private record IssuedToken(String raw, Duration ttl) {
    }

    private final UserSessionRepository sessionRepository;
    private final RefreshTokenRepository tokenRepository;
    private final UserRepository userRepository;

    @Value("${app.jwt.refresh-ttl-days:7}")
    private long refreshTtlDays;

    @Value("${app.jwt.session-max-days:30}")
    private long sessionMaxDays;

    /** Tạo phiên mới (lúc đăng nhập/đăng ký) và refresh token đầu tiên. */
    @Transactional
    public NewSession createSession(Long userId, ClientInfo client) {
        LocalDateTime now = LocalDateTime.now();

        UserSession session = new UserSession();
        session.setUser(userRepository.getReferenceById(userId));
        session.setCreatedAt(now);
        session.setExpiresAt(now.plusDays(sessionMaxDays));
        session.setIpAddress(client.ip());
        session.setUserAgent(client.userAgent());
        sessionRepository.save(session);

        IssuedToken token = issueRefreshToken(session, now);
        AUDIT.info("event=SESSION_CREATED sessionId={} userId={} ip={}", session.getId(), userId, client.ip());
        return new NewSession(session.getId(), token.raw(), token.ttl());
    }

    /**
     * Đổi refresh token cũ lấy token mới (mỗi token chỉ dùng được một lần).
     * Không ném exception khi thất bại để giao dịch vẫn được commit (việc thu hồi phiên phải được lưu lại).
     */
    @Transactional
    public Rotation rotate(String rawToken, ClientInfo client) {
        LocalDateTime now = LocalDateTime.now();

        Optional<RefreshToken> found = tokenRepository.findByTokenHashForUpdate(sha256Hex(rawToken));
        if (found.isEmpty()) {
            return Rotation.failed(Status.INVALID);
        }

        RefreshToken token = found.get();
        UserSession session = token.getSession();

        RefreshTokenPolicy.Decision decision = RefreshTokenPolicy.decide(
                session.getRevokedAt() != null, session.getExpiresAt(),
                token.getExpiresAt(), token.getUsedAt(), now);

        if (decision == RefreshTokenPolicy.Decision.INVALID) {
            return Rotation.failed(Status.INVALID);
        }
        if (decision == RefreshTokenPolicy.Decision.REUSED) {
            // Token đã đổi rồi mà vẫn có người dùng lại: nhiều khả năng bị đánh cắp -> hủy cả phiên
            revoke(session, "REUSE_DETECTED", now);
            AUDIT.warn("event=REFRESH_REUSE_DETECTED sessionId={} userId={} ip={}",
                    session.getId(), session.getUser().getUserId(), client.ip());
            return Rotation.failed(Status.REUSED);
        }

        User user = session.getUser();
        if (!user.isEnabled()) {
            revoke(session, "USER_DISABLED", now);
            return Rotation.failed(Status.INVALID);
        }

        if (token.getUsedAt() == null) {
            token.setUsedAt(now); // trong khoảng ân hạn thì giữ nguyên mốc dùng đầu tiên
        }

        IssuedToken next = issueRefreshToken(session, now);
        return new Rotation(Status.OK, AppUserDetails.from(user), session.getId(), next.raw(), next.ttl());
    }

    /** Đăng xuất thiết bị hiện tại: thu hồi phiên chứa refresh token này. */
    @Transactional
    public void revokeByRefreshToken(String rawToken, String reason) {
        LocalDateTime now = LocalDateTime.now();
        tokenRepository.findByTokenHash(sha256Hex(rawToken)).ifPresent(token -> {
            revoke(token.getSession(), reason, now);
            AUDIT.info("event=SESSION_REVOKED sessionId={} reason={}", token.getSession().getId(), reason);
        });
    }

    /** Đăng xuất mọi thiết bị của một tài khoản. */
    @Transactional
    public void revokeAllForUser(Long userId, String reason) {
        LocalDateTime now = LocalDateTime.now();
        List<UserSession> active = sessionRepository.findByUser_UserIdAndRevokedAtIsNull(userId);
        active.forEach(session -> revoke(session, reason, now));
        AUDIT.info("event=ALL_SESSIONS_REVOKED userId={} count={} reason={}", userId, active.size(), reason);
    }

    /** JwtAuthFilter gọi ở mỗi request: phiên này còn hiệu lực không? */
    public boolean isActive(String sessionId) {
        return sessionRepository.existsByIdAndRevokedAtIsNullAndExpiresAtAfter(sessionId, LocalDateTime.now());
    }

    /** Dọn token và phiên đã hết hạn để bảng không phình mãi. */
    @Scheduled(cron = "0 30 3 * * *")
    @Transactional
    public void purgeExpired() {
        LocalDateTime now = LocalDateTime.now();
        // Hạn của token luôn <= hạn của phiên, nên xóa token trước rồi mới xóa phiên là an toàn với khóa ngoại
        long tokens = tokenRepository.deleteByExpiresAtBefore(now);
        long sessions = sessionRepository.deleteByExpiresAtBefore(now);
        AUDIT.info("event=SESSIONS_PURGED tokens={} sessions={}", tokens, sessions);
    }

    // ---------------------------------------------------------------------------------------------

    private IssuedToken issueRefreshToken(UserSession session, LocalDateTime now) {
        String raw = generateRawToken();

        LocalDateTime expiresAt = now.plusDays(refreshTtlDays);
        if (expiresAt.isAfter(session.getExpiresAt())) {
            expiresAt = session.getExpiresAt(); // không bao giờ vượt quá hạn tuyệt đối của phiên
        }

        RefreshToken token = new RefreshToken();
        token.setSession(session);
        token.setTokenHash(sha256Hex(raw));
        token.setCreatedAt(now);
        token.setExpiresAt(expiresAt);
        tokenRepository.save(token);

        return new IssuedToken(raw, Duration.between(now, expiresAt));
    }

    private void revoke(UserSession session, String reason, LocalDateTime now) {
        if (session.getRevokedAt() == null) {
            session.setRevokedAt(now);
            session.setRevokedReason(reason);
        }
    }

    /** 256 bit ngẫu nhiên từ SecureRandom, đổi sang chuỗi an toàn để đặt trong cookie (43 ký tự). */
    private static String generateRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("JVM không hỗ trợ SHA-256", e);
        }
    }
}
