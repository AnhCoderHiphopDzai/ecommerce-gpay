package com.example.backend.controller;

import com.example.backend.dto.LoginRequest;
import com.example.backend.security.AuthRateLimiter;
import com.example.backend.security.TooManyAttemptsException;
import com.example.backend.service.AuthService;
import com.example.backend.service.SessionService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@Tag("unit")
class AuthControllerTest {

    @Test
    void limitsRepeatedFailedLoginsBeforeCallingTheAuthenticationServiceAgain() {
        AuthService authService = mock(AuthService.class);
        AuthRateLimiter rateLimiter = new AuthRateLimiter();
        AuthController controller = new AuthController(authService, mock(SessionService.class), rateLimiter);
        ReflectionTestUtils.setField(controller, "loginMaxAttempts", 2);
        ReflectionTestUtils.setField(controller, "registrationMaxAttempts", 2);
        ReflectionTestUtils.setField(controller, "rateLimitWindowMinutes", 1L);
        ReflectionTestUtils.setField(controller, "refreshCookieSecure", false);

        LoginRequest request = new LoginRequest();
        request.setEmail("user@example.test");
        request.setPassword("wrong-password");
        MockHttpServletRequest servletRequest = new MockHttpServletRequest();
        servletRequest.setRemoteAddr("203.0.113.17");
        when(authService.login(any(LoginRequest.class), any()))
                .thenThrow(new BadCredentialsException("bad credentials"));

        assertThrows(BadCredentialsException.class, () -> controller.login(request, servletRequest));
        assertThrows(BadCredentialsException.class, () -> controller.login(request, servletRequest));

        TooManyAttemptsException limited = assertThrows(TooManyAttemptsException.class,
                () -> controller.login(request, servletRequest));
        var response = controller.onTooManyAttempts(limited);

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, response.getStatusCode());
        assertNotNull(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER));
    }
}
