package com.example.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class AuthResponse {
    private String accessToken;
    private String tokenType;   // luôn là "Bearer"
    private long expiresIn;     // số giây token còn hiệu lực
    private UserResponse user;
}
