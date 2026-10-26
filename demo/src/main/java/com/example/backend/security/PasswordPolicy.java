package com.example.backend.security;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Chính sách mật khẩu theo hướng dẫn NIST 800-63B: ưu tiên ĐỘ DÀI, chặn mật khẩu phổ biến
 * và mật khẩu chứa thông tin cá nhân, không bắt buộc "phải có ký tự đặc biệt".
 */
public final class PasswordPolicy {

    public static final int MIN_LENGTH = 12;
    public static final int MAX_LENGTH = 72; // BCrypt chỉ xử lý tối đa 72 byte

    private static final Set<String> COMMON = new HashSet<>(Arrays.asList(
            "123456789012", "1234567890123", "12345678901234", "123456789abc", "abcdefghijkl", "abc123456789",
            "qwertyuiop12", "qwertyuiopas", "qwertyuiop123", "1q2w3e4r5t6y", "1qaz2wsx3edc", "1qazxsw23edc",
            "qazwsxedcrfv", "zxcvbnm12345", "asdfghjkl123", "password1234", "password12345", "passwordpassword",
            "password123456", "p@ssw0rd1234", "iloveyou1234", "iloveyou12345", "letmein123456", "welcome12345",
            "welcome123456", "administrator", "admin12345678", "admin123456789", "changeme1234", "changeme12345",
            "000000000000", "111111111111", "aaaaaaaaaaaa", "matkhau12345", "matkhau123456", "vietnam123456",
            "vietnam12345", "hanoi1234567", "saigon123456", "123456789qwe", "qwe123456789", "1234qwer1234",
            "987654321098", "112233445566", "123123123123", "abcabcabcabc", "football1234", "monkey123456",
            "dragon123456", "master123456", "sunshine1234", "princess1234", "superman1234"));

    private PasswordPolicy() {
    }

    /** Trả về lý do mật khẩu bị từ chối, hoặc rỗng nếu chấp nhận được. */
    public static Optional<String> violation(String password, String email, String fullName) {
        if (password == null || password.length() < MIN_LENGTH) {
            return Optional.of("Mật khẩu cần ít nhất " + MIN_LENGTH + " ký tự");
        }
        if (password.length() > MAX_LENGTH) {
            return Optional.of("Mật khẩu tối đa " + MAX_LENGTH + " ký tự");
        }

        String lower = password.toLowerCase(Locale.ROOT);

        if (COMMON.contains(lower)) {
            return Optional.of("Mật khẩu này quá phổ biến, hãy chọn mật khẩu khác");
        }
        if (lower.chars().distinct().count() == 1) {
            return Optional.of("Mật khẩu không được chỉ lặp lại một ký tự");
        }

        String normalizedPassword = normalize(password);

        if (email != null) {
            int at = email.indexOf('@');
            String local = normalize(at > 0 ? email.substring(0, at) : email);
            if (local.length() >= 4 && normalizedPassword.contains(local)) {
                return Optional.of("Mật khẩu không được chứa phần tên trong email");
            }
        }
        if (fullName != null) {
            String name = normalize(fullName);
            if (name.length() >= 4 && normalizedPassword.contains(name)) {
                return Optional.of("Mật khẩu không được chứa họ tên của bạn");
            }
        }
        return Optional.empty();
    }

    /** Bỏ dấu tiếng Việt, đổi chữ thường, chỉ giữ chữ và số: "Nguyễn Văn An" -> "nguyenvanan". */
    private static String normalize(String text) {
        String decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace('đ', 'd').replace('Đ', 'D');
        return decomposed.replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]", "");
    }
}
