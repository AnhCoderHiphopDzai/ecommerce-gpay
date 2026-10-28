package com.example.backend.config;

import com.example.backend.security.JwtAuthFilter;
import com.example.backend.security.JwtService;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.io.IOException;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity   // thiếu dòng này thì @PreAuthorize bị bỏ qua âm thầm
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   JwtService jwtService,
                                                   UserDetailsService userDetailsService) throws Exception {
        http
                // CSRF chỉ nguy hiểm khi trình duyệt TỰ gửi kèm thông tin đăng nhập (cookie).
                // Ở đây token nằm trong header Authorization do frontend chủ động gắn, nên tắt CSRF.
                // (Webhook của G-Pay là POST không có CSRF token, bật CSRF sẽ chặn mất webhook.)
                .csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // --- Công khai ---
                        .requestMatchers("/api/auth/register", "/api/auth/login").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/products", "/api/products/**").permitAll()
                        // G-Pay gọi server-to-server nên không có JWT, được bảo vệ bằng chữ ký RSA trong PaymentController
                        .requestMatchers("/api/payment/webhook").permitAll()
                        // Trình duyệt người dùng được G-Pay chuyển về, chỉ redirect sang frontend
                        .requestMatchers("/api/payment/callback").permitAll()
                        // WebSocket (SockJS): trình duyệt không gắn được header Authorization khi bắt tay
                        .requestMatchers("/ws", "/ws/**").permitAll()
                        // Trang /error nội bộ của Spring Boot; nếu chặn, mọi lỗi 4xx/5xx sẽ bị biến thành 401/403
                        .requestMatchers("/error").permitAll()
                        // --- Chỉ ADMIN ---
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .exceptionHandling(ex -> ex
                        // Chưa đăng nhập / token sai / hết hạn -> 401 (mặc định Spring trả 403 gây khó phân biệt)
                        .authenticationEntryPoint((req, res, e) ->
                                writeError(res, HttpStatus.UNAUTHORIZED,
                                        "Bạn chưa đăng nhập hoặc phiên đăng nhập đã hết hạn"))
                        // Đã đăng nhập nhưng không đủ quyền -> 403
                        .accessDeniedHandler((req, res, e) ->
                                writeError(res, HttpStatus.FORBIDDEN,
                                        "Bạn không có quyền thực hiện thao tác này")))
                .addFilterBefore(new JwtAuthFilter(jwtService, userDetailsService),
                        UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * CORS tập trung một chỗ (thay cho @CrossOrigin("*") trên từng controller).
     * Chỉ áp dụng cho /api/**: /ws do SockJS tự xử lý CORS (xem WebSocketConfig), tránh gửi trùng header.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:http://localhost:5173}") List<String> allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(allowedOrigins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "Accept"));
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    // Tự ghép JSON thay vì dùng bean JSON của Jackson: Spring Boot 3 dùng com.fasterxml.jackson, Boot 4 dùng tools.jackson,
    // nên tránh phụ thuộc vào Jackson ở đây. "message" luôn là chuỗi hằng, không chứa dấu nháy hay ký tự đặc biệt.
    private static void writeError(HttpServletResponse res, HttpStatus status, String message) throws IOException {
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        res.setCharacterEncoding("UTF-8");
        res.getWriter().write("{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}");
    }
}