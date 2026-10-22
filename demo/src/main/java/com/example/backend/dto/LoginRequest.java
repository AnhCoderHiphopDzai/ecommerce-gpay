package com.example.backend.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class LoginRequest {

    @NotBlank(message = "Vui lòng nhập email")
    private String email;

    @NotBlank(message = "Vui lòng nhập mật khẩu")
    private String password;
}
