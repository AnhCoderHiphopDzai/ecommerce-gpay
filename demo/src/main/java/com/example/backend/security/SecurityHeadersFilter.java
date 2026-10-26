package com.example.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Thêm các header bảo mật vào mọi phản hồi của API.
 * (Spring Security đã tự thêm X-Frame-Options, Cache-Control: no-store và HSTS khi chạy HTTPS.)
 * Không gắn @Component: SecurityConfig tự tạo filter này.
 */
public class SecurityHeadersFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        // API chỉ trả JSON nên cấm tải bất kỳ tài nguyên nào và cấm bị nhúng vào khung (iframe)
        response.setHeader("Content-Security-Policy",
                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Referrer-Policy", "no-referrer");
        response.setHeader("Permissions-Policy", "geolocation=(), microphone=(), camera=()");
        chain.doFilter(request, response);
    }
}
