package com.example.backend.security;

/** Lỗi gắn với một ô nhập cụ thể (ví dụ mật khẩu quá yếu), để frontend hiện ngay dưới ô đó. */
public class FieldValidationException extends RuntimeException {

    private final String field;

    public FieldValidationException(String field, String message) {
        super(message);
        this.field = field;
    }

    public String getField() {
        return field;
    }
}
