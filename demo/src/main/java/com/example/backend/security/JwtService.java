package com.example.backend.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Date;

/**
 * Tạo và kiểm tra JWT (ký HS256).
 * Token chỉ chứa: sub (email), iat (giờ tạo), exp (giờ hết hạn). Không đặt dữ liệu nhạy cảm vào token.
 */
@Service
public class JwtService {

    private final SecretKey key;
    private final long expirationMillis;

    public JwtService(@Value("${app.jwt.secret}") String secret,
                      @Value("${app.jwt.expiration-minutes:60}") long expirationMinutes) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < 32) {
            // Fail-fast: không cho ứng dụng chạy với secret yếu
            throw new IllegalStateException(
                    "app.jwt.secret phải dài ít nhất 32 ký tự (256 bit). Tạo chuỗi ngẫu nhiên bằng: openssl rand -base64 48");
        }
        this.key = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMillis = Duration.ofMinutes(expirationMinutes).toMillis();
    }

    public String generateToken(String email) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(email)
                .issuedAt(new Date(now))
                .expiration(new Date(now + expirationMillis))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    /**
     * Kiểm tra chữ ký + hạn dùng rồi trả về email trong token.
     * Token sai/hết hạn/bị sửa sẽ ném JwtException.
     */
    public String extractEmail(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getSubject();
    }

    public long getExpiresInSeconds() {
        return expirationMillis / 1000;
    }
}
