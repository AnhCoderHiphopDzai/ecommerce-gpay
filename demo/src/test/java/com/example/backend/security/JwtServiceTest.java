package com.example.backend.security;

import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

@Tag("unit")
class JwtServiceTest {

    private static final String SECRET = "ci-test-jwt-secret-not-for-deployment-1234567890";

    @Test
    void roundTripsTheEmailAndSessionId() {
        JwtService service = new JwtService(SECRET, 15);

        String token = service.generateToken("user@example.test", "session-123");
        JwtService.SessionIdentity identity = service.extractIdentity(token);

        assertEquals("user@example.test", identity.email());
        assertEquals("session-123", identity.sessionId());
    }

    @Test
    void rejectsWeakSecretsAndTamperedTokens() {
        assertThrows(IllegalStateException.class, () -> new JwtService("too-short", 15));

        JwtService service = new JwtService(SECRET, 15);
        String token = service.generateToken("user@example.test", "session-123");
        assertThrows(JwtException.class, () -> service.extractIdentity(token + "tampered"));
    }
}
