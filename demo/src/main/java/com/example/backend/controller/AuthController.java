package com.example.backend.controller;

import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.UserResponse;
import com.example.backend.security.AppUserDetails;
import com.example.backend.security.AuthRateLimiter;
import com.example.backend.security.ClientInfo;
import com.example.backend.security.FieldValidationException;
import com.example.backend.security.TooManyAttemptsException;
import com.example.backend.service.AuthService;
import com.example.backend.service.SessionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private static final String REFRESH_COOKIE = "refresh_token";

    private final AuthService authService;
    private final SessionService sessionService;
    private final AuthRateLimiter rateLimiter;

    @Value("${app.auth.login-max-attempts:10}")
    private int loginMaxAttempts;

    @Value("${app.auth.registration-max-attempts:5}")
    private int registrationMaxAttempts;

    @Value("${app.auth.rate-limit-window-minutes:10}")
    private long rateLimitWindowMinutes;

    @Value("${app.auth.refresh-cookie-secure:false}")
    private boolean refreshCookieSecure;

    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request,
                                                 HttpServletRequest servletRequest) {
        ClientInfo client = ClientInfo.from(servletRequest);
        String key = rateLimitKey("register", client);
        checkRateLimit(key, registrationMaxAttempts);
        AuthService.AuthenticatedSession session = authService.register(request, client);
        rateLimiter.reset(key);
        return authenticatedResponse(HttpStatus.CREATED, session);
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                              HttpServletRequest servletRequest) {
        ClientInfo client = ClientInfo.from(servletRequest);
        String key = rateLimitKey("login", client);
        checkRateLimit(key, loginMaxAttempts);
        AuthService.AuthenticatedSession session = authService.login(request, client);
        rateLimiter.reset(key);
        return authenticatedResponse(HttpStatus.OK, session);
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refresh(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken,
                                     HttpServletRequest servletRequest) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return unauthorizedAndClearCookie();
        }
        try {
            return authenticatedResponse(HttpStatus.OK, authService.refresh(refreshToken, ClientInfo.from(servletRequest)));
        } catch (ResponseStatusException ex) {
            return unauthorizedAndClearCookie();
        }
    }

    /** Thu hồi phiên của thiết bị hiện tại. Không cần JWT còn hạn vì refresh cookie chính là định danh phiên. */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@CookieValue(name = REFRESH_COOKIE, required = false) String refreshToken) {
        if (refreshToken != null && !refreshToken.isBlank()) {
            sessionService.revokeByRefreshToken(refreshToken, "USER_LOGOUT");
        }
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .build();
    }

    @PostMapping("/logout-all")
    public ResponseEntity<Void> logoutAll(@AuthenticationPrincipal AppUserDetails principal) {
        sessionService.revokeAllForUser(principal.getId(), "USER_LOGOUT_ALL");
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .build();
    }

    /** Frontend gọi khi tải lại trang để biết token cũ còn dùng được không và ai đang đăng nhập. */
    @GetMapping("/me")
    public UserResponse me(@AuthenticationPrincipal AppUserDetails principal) {
        return UserResponse.from(principal);
    }

    private ResponseEntity<AuthResponse> authenticatedResponse(HttpStatus status,
                                                               AuthService.AuthenticatedSession session) {
        return ResponseEntity.status(status)
                .header(HttpHeaders.SET_COOKIE, refreshCookie(session.refreshToken(), session.refreshTtl()).toString())
                .body(session.response());
    }

    private ResponseEntity<Map<String, String>> unauthorizedAndClearCookie() {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, expiredRefreshCookie().toString())
                .body(Map.of("message", "Phiên đăng nhập không còn hợp lệ"));
    }

    private ResponseCookie refreshCookie(String rawToken, Duration ttl) {
        return ResponseCookie.from(REFRESH_COOKIE, rawToken)
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(ttl)
                .build();
    }

    private ResponseCookie expiredRefreshCookie() {
        return ResponseCookie.from(REFRESH_COOKIE, "")
                .httpOnly(true)
                .secure(refreshCookieSecure)
                .sameSite("Strict")
                .path("/api/auth")
                .maxAge(Duration.ZERO)
                .build();
    }

    private void checkRateLimit(String key, int maxAttempts) {
        int attempts = rateLimiter.increment(key, Duration.ofMinutes(rateLimitWindowMinutes));
        if (attempts > maxAttempts) {
            throw new TooManyAttemptsException("Bạn đã thử quá nhiều lần. Vui lòng thử lại sau.",
                    rateLimiter.retryAfterSeconds(key));
        }
    }

    private static String rateLimitKey(String action, ClientInfo client) {
        return action + ':' + (client.ip() == null ? "unknown" : client.ip());
    }

    // ===== Lỗi của riêng nhóm /api/auth, luôn trả JSON dạng {"message": "..."} =====

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> onValidationError(MethodArgumentNotValidException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(fe -> errors.putIfAbsent(fe.getField(), fe.getDefaultMessage()));
        return ResponseEntity.badRequest().body(Map.of(
                "message", "Dữ liệu chưa hợp lệ, vui lòng kiểm tra lại",
                "errors", errors));
    }

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> onStatusError(ResponseStatusException ex) {
        String message = ex.getReason() != null ? ex.getReason() : "Yêu cầu không hợp lệ";
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message", message));
    }

    @ExceptionHandler(FieldValidationException.class)
    public ResponseEntity<Map<String, Object>> onFieldValidation(FieldValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "message", "Dữ liệu chưa hợp lệ, vui lòng kiểm tra lại",
                "errors", Map.of(ex.getField(), ex.getMessage())));
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<Map<String, String>> onTooManyAttempts(TooManyAttemptsException ex) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.getRetryAfterSeconds()))
                .body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<Map<String, Object>> onBadCredentials(AuthenticationException ex) {
        // Cố ý dùng một thông báo chung cho mọi lý do thất bại
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Email hoặc mật khẩu không đúng"));
    }
}
