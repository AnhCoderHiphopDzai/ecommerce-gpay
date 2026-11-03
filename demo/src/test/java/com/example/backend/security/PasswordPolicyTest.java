package com.example.backend.security;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Tag("unit")
class PasswordPolicyTest {

    @Test
    void acceptsALongPasswordWithoutPersonalInformation() {
        assertTrue(PasswordPolicy.violation("CorrectHorseBatteryStaple!2026", "an@example.test", "Nguyễn Văn An").isEmpty());
    }

    @Test
    void rejectsShortAndOverlongPasswords() {
        assertTrue(PasswordPolicy.violation("short", "an@example.test", "An").isPresent());
        assertTrue(PasswordPolicy.violation("a".repeat(73), "an@example.test", "An").isPresent());
        assertTrue(PasswordPolicy.violation("ă".repeat(37), "an@example.test", "An").isPresent(),
                "37 ký tự ă là 74 byte UTF-8 và BCrypt sẽ cắt ngắn nếu không chặn");
    }

    @Test
    void rejectsCommonAndRepeatedPasswords() {
        assertTrue(PasswordPolicy.violation("Password12345", "an@example.test", "An").isPresent());
        assertTrue(PasswordPolicy.violation("a".repeat(12), "an@example.test", "An").isPresent());
    }

    @Test
    void rejectsEmailAndVietnameseNameFragmentsAfterNormalization() {
        assertTrue(PasswordPolicy.violation("safequang2026-password", "quang@example.test", "Nguyễn Văn An").isPresent());
        assertTrue(PasswordPolicy.violation("prefixnguyenvanansuffix2026", "other@example.test", "Nguyễn Văn An").isPresent());
        assertFalse(PasswordPolicy.violation("TwelveChars!42", "other@example.test", "Nguyễn Văn An").isPresent());
    }
}
