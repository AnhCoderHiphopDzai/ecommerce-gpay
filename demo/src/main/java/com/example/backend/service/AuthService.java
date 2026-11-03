package com.example.backend.service;

import com.example.backend.dao.UserRepository;
import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.UserResponse;
import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.security.AppUserDetails;
import com.example.backend.security.ClientInfo;
import com.example.backend.security.FieldValidationException;
import com.example.backend.security.CustomUserDetailsService;
import com.example.backend.security.JwtService;
import com.example.backend.security.PasswordPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final SessionService sessionService;

    public record AuthenticatedSession(AuthResponse response, String refreshToken, Duration refreshTtl) {
    }

    public AuthenticatedSession register(RegisterRequest request, ClientInfo client) {
        String email = CustomUserDetailsService.normalizeEmail(request.getEmail());

        PasswordPolicy.violation(request.getPassword(), email, request.getFullName())
                .ifPresent(message -> {
                    throw new FieldValidationException("password", message);
                });

        if (userRepository.existsByEmail(email)) {
            throw emailTaken();
        }

        User user = new User();
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setFullName(request.getFullName().trim());
        user.setPhone(request.getPhone().trim());
        user.setRole(Role.USER); // luôn là USER, không bao giờ nhận quyền từ client

        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException e) {
            // Hai request đăng ký cùng email đua nhau: unique constraint của DB chặn request chậm hơn
            throw emailTaken();
        }

        // Đăng ký xong đăng nhập luôn, frontend không phải gọi login lần nữa
        return buildAuthenticatedSession(AppUserDetails.from(user), client);
    }

    public AuthenticatedSession login(LoginRequest request, ClientInfo client) {
        // Sai email hoặc sai mật khẩu đều ném BadCredentialsException (cùng một thông báo, không lộ email nào tồn tại)
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        CustomUserDetailsService.normalizeEmail(request.getEmail()),
                        request.getPassword()));

        return buildAuthenticatedSession((AppUserDetails) authentication.getPrincipal(), client);
    }

    public AuthenticatedSession refresh(String rawRefreshToken, ClientInfo client) {
        SessionService.Rotation rotation = sessionService.rotate(rawRefreshToken, client);
        if (rotation.status() != SessionService.Status.OK) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Phiên đăng nhập không còn hợp lệ");
        }

        String accessToken = jwtService.generateToken(rotation.user().getEmail(), rotation.sessionId());
        AuthResponse response = new AuthResponse(accessToken, "Bearer", jwtService.getExpiresInSeconds(),
                UserResponse.from(rotation.user()));
        return new AuthenticatedSession(response, rotation.refreshToken(), rotation.refreshTtl());
    }

    private AuthenticatedSession buildAuthenticatedSession(AppUserDetails principal, ClientInfo client) {
        SessionService.NewSession session = sessionService.createSession(principal.getId(), client);
        String token = jwtService.generateToken(principal.getEmail(), session.sessionId());
        AuthResponse response = new AuthResponse(token, "Bearer", jwtService.getExpiresInSeconds(),
                UserResponse.from(principal));
        return new AuthenticatedSession(response, session.refreshToken(), session.refreshTtl());
    }

    private ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được đăng ký");
    }
}
