package com.example.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * Một lần đăng nhập trên một thiết bị = một phiên.
 * Thu hồi phiên (revokedAt) nghĩa là đăng xuất ngay lập tức, kể cả khi access token chưa hết hạn.
 */
@Getter
@Setter
@NoArgsConstructor
@Entity
@Table(name = "user_sessions", indexes = @Index(name = "idx_user_sessions_user", columnList = "user_id"))
public class UserSession {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "session_id", length = 36)
    private String id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    // Hạn tuyệt đối của phiên: hết hạn này thì bắt buộc đăng nhập lại dù vẫn đang hoạt động
    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @Column(name = "revoked_reason", length = 30)
    private String revokedReason;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 255)
    private String userAgent;
}
