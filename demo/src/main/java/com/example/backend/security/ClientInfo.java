package com.example.backend.security;

import jakarta.servlet.http.HttpServletRequest;

/** Thông tin máy khách, dùng để ghi nhật ký và giới hạn tần suất. */
public record ClientInfo(String ip, String userAgent) {

    public static ClientInfo from(HttpServletRequest request) {
        String userAgent = request.getHeader("User-Agent");
        if (userAgent != null && userAgent.length() > 255) {
            userAgent = userAgent.substring(0, 255);
        }
        // Sau reverse proxy (nginx, load balancer) cần bật server.forward-headers-strategy để getRemoteAddr() ra IP thật
        return new ClientInfo(request.getRemoteAddr(), userAgent);
    }
}
