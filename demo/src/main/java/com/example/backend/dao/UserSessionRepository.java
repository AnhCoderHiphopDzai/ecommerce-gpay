package com.example.backend.dao;

import com.example.backend.model.UserSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface UserSessionRepository extends JpaRepository<UserSession, String> {

    /** Phiên còn dùng được: chưa bị thu hồi và chưa quá hạn tuyệt đối. */
    boolean existsByIdAndRevokedAtIsNullAndExpiresAtAfter(String id, LocalDateTime now);

    List<UserSession> findByUser_UserIdAndRevokedAtIsNull(Long userId);

    long deleteByExpiresAtBefore(LocalDateTime time);
}
