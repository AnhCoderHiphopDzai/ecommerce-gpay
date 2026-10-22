package com.example.backend.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank(message = "Vui lòng nhập họ và tên")
    @Size(max = 100, message = "Họ và tên tối đa 100 ký tự")
    private String fullName;

    @NotBlank(message = "Vui lòng nhập số điện thoại")
    @Pattern(regexp = "^\\+?\\d{9,14}$", message = "Số điện thoại gồm 9-14 chữ số, có thể bắt đầu bằng +")
    private String phone;

    @NotBlank(message = "Vui lòng nhập email")
    @Email(message = "Email không hợp lệ")
    @Size(max = 254, message = "Email tối đa 254 ký tự")
    private String email;

    // BCrypt chỉ xử lý tối đa 72 byte nên giới hạn 72 ký tự
    @NotBlank(message = "Vui lòng nhập mật khẩu")
    @Size(min = 8, max = 72, message = "Mật khẩu phải từ 8 đến 72 ký tự")
    private String password;

    // Cố ý KHÔNG có trường "role": client không được tự chọn quyền
}
