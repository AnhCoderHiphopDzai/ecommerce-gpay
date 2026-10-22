package com.example.backend.service;

import com.example.backend.dao.UserRepository;
import com.example.backend.dto.AuthResponse;
import com.example.backend.dto.LoginRequest;
import com.example.backend.dto.RegisterRequest;
import com.example.backend.dto.UserResponse;
import com.example.backend.model.Role;
import com.example.backend.model.User;
import com.example.backend.security.AppUserDetails;
import com.example.backend.security.CustomUserDetailsService;
import com.example.backend.security.JwtService;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;

    public AuthResponse register(RegisterRequest request) {
        String email = CustomUserDetailsService.normalizeEmail(request.getEmail());

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
        return buildAuthResponse(AppUserDetails.from(user));
    }

    public AuthResponse login(LoginRequest request) {
        // Sai email hoặc sai mật khẩu đều ném BadCredentialsException (cùng một thông báo, không lộ email nào tồn tại)
        Authentication authentication = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(
                        CustomUserDetailsService.normalizeEmail(request.getEmail()),
                        request.getPassword()));

        return buildAuthResponse((AppUserDetails) authentication.getPrincipal());
    }

    private AuthResponse buildAuthResponse(AppUserDetails principal) {
        String token = jwtService.generateToken(principal.getEmail());
        return new AuthResponse(token, "Bearer", jwtService.getExpiresInSeconds(), UserResponse.from(principal));
    }

    private ResponseStatusException emailTaken() {
        return new ResponseStatusException(HttpStatus.CONFLICT, "Email này đã được đăng ký");
    }
}
