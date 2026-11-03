package com.example.backend.security;

import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import com.example.backend.service.SessionService;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Chạy trước mỗi request: đọc "Authorization: Bearer <token>", xác thực rồi đặt người dùng vào SecurityContext.
 *
 * - Không có token / token sai / hết hạn: KHÔNG ném lỗi ở đây, chỉ coi như chưa đăng nhập.
 *   Endpoint công khai vẫn chạy bình thường, endpoint cần đăng nhập sẽ trả 401.
 * - Cố ý KHÔNG gắn @Component: SecurityConfig tự tạo filter này để Spring Boot
 *   không đăng ký nó thêm một lần nữa vào servlet container.
 */
@Slf4j
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserDetailsService userDetailsService;
    private final SessionService sessionService;

    public JwtAuthFilter(JwtService jwtService, UserDetailsService userDetailsService,
                         SessionService sessionService) {
        this.jwtService = jwtService;
        this.userDetailsService = userDetailsService;
        this.sessionService = sessionService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);

        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                JwtService.SessionIdentity identity = jwtService.extractIdentity(token);

                // Mỗi access token thuộc về một phiên cụ thể. Thu hồi phiên có hiệu lực ngay,
                // thay vì phải chờ JWT hết hạn.
                if (!sessionService.isActive(identity.sessionId())) {
                    chain.doFilter(request, response);
                    return;
                }

                // Luôn đọc lại tài khoản từ DB: khóa/xóa tài khoản có hiệu lực ngay, không chờ token hết hạn
                UserDetails user = userDetailsService.loadUserByUsername(identity.email());

                if (user.isEnabled()) {
                    UsernamePasswordAuthenticationToken authentication =
                            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                }
            } catch (JwtException | IllegalArgumentException | UsernameNotFoundException e) {
                log.debug("Bỏ qua JWT không hợp lệ: {}", e.getMessage());
            }
        }

        chain.doFilter(request, response);
    }
}
