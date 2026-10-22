package com.example.backend.dto;

import com.example.backend.security.AppUserDetails;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Thông tin tài khoản trả về cho frontend (không có mật khẩu).
 */
@Getter
@AllArgsConstructor
public class UserResponse {
    private Long id;
    private String email;
    private String fullName;
    private String phone;
    private String role;

    public static UserResponse from(AppUserDetails u) {
        return new UserResponse(u.getId(), u.getEmail(), u.getFullName(), u.getPhone(), u.getRole().name());
    }
}

