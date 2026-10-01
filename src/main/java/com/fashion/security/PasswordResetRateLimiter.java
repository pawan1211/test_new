package com.fashion.security;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PasswordResetRateLimiter {

    private static final int FORGOT_PASSWORD_MAX_ATTEMPTS = 3;
    private static final int FORGOT_PASSWORD_WINDOW_MINUTES = 10;
    private static final int RESET_PASSWORD_MAX_ATTEMPTS = 5;
    private static final int RESET_PASSWORD_WINDOW_MINUTES = 10;

    private final Map<String, RateWindow> forgotPasswordWindows = new ConcurrentHashMap<>();
    private final Map<String, RateWindow> resetPasswordWindows = new ConcurrentHashMap<>();

    public synchronized void checkForgotPasswordLimit(String email) {
        checkLimit(forgotPasswordWindows, email.toLowerCase(), FORGOT_PASSWORD_MAX_ATTEMPTS, FORGOT_PASSWORD_WINDOW_MINUTES);
    }

    public synchronized void checkResetPasswordLimit(String token) {
        checkLimit(resetPasswordWindows, token, RESET_PASSWORD_MAX_ATTEMPTS, RESET_PASSWORD_WINDOW_MINUTES);
    }

    private void checkLimit(Map<String, RateWindow> windows, String key, int maxAttempts, int windowMinutes) {
        Instant now = Instant.now();
        RateWindow window = windows.get(key);
        if (window == null || window.isExpired(now, windowMinutes)) {
            window = new RateWindow(now);
            windows.put(key, window);
        }
        if (window.count >= maxAttempts) {
            throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                "Too many attempts. Please try again later."
            );
        }
        window.count++;
    }

    private static class RateWindow {
        Instant start;
        int count;

        RateWindow(Instant start) {
            this.start = start;
            this.count = 0;
        }

        boolean isExpired(Instant now, int windowMinutes) {
            return start.plusSeconds(windowMinutes * 60L).isBefore(now);
        }
    }
}
