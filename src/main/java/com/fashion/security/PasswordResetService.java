package com.fashion.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

@Service
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);
    private static final int TOKEN_BYTES = 32;
    private static final int MIN_PASSWORD_LENGTH = 10;

    private final JdbcTemplate db;
    private final PasswordResetTokenRepository tokenRepository;
    private final EmailService emailService;
    private final PasswordResetRateLimiter rateLimiter;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${app.password-reset.token-expiry-minutes:15}")
    private int tokenExpiryMinutes;

    @Value("${app.password-reset.frontend-base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    public PasswordResetService(JdbcTemplate db,
                                  PasswordResetTokenRepository tokenRepository,
                                  EmailService emailService,
                                  PasswordResetRateLimiter rateLimiter) {
        this.db = db;
        this.tokenRepository = tokenRepository;
        this.emailService = emailService;
        this.rateLimiter = rateLimiter;
    }

    public void requestPasswordReset(String email) {
        if (email == null || !email.matches("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid email address is required");
        }
        String normalizedEmail = email.trim().toLowerCase(Locale.ROOT);
        rateLimiter.checkForgotPasswordLimit(normalizedEmail);

        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, email FROM customer_users WHERE lower(email) = lower(?) AND enabled = true",
            normalizedEmail
        );

        if (rows.isEmpty()) {
            log.info("Password reset requested for unknown email: {}", normalizedEmail);
            return;
        }

        UUID customerId = (UUID) rows.get(0).get("id");
        String customerEmail = (String) rows.get(0).get("email");

        String rawToken = generateToken();
        String tokenHash = hashToken(rawToken);
        Instant expiresAt = Instant.now().plusSeconds(tokenExpiryMinutes * 60L);

        tokenRepository.insert(UUID.randomUUID(), customerId, tokenHash, expiresAt);

        String resetLink = frontendBaseUrl + "/reset-password?token=" + rawToken;
        emailService.sendPasswordResetEmail(customerEmail, resetLink);

        log.info("Password reset token generated for customer: {}", customerId);
    }

    @Transactional
    public void resetPassword(String token, String newPassword, String confirmPassword) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token is required");
        }
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Password must be at least " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (!newPassword.equals(confirmPassword)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Passwords do not match");
        }

        rateLimiter.checkResetPasswordLimit(token);

        String tokenHash = hashToken(token);
        Map<String, Object> tokenRow = tokenRepository.findByTokenHash(tokenHash);

        if (tokenRow == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired reset token");
        }

        Instant expiresAt = ((java.sql.Timestamp) tokenRow.get("expires_at")).toInstant();
        if (expiresAt.isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has expired");
        }

        if (tokenRow.get("used_at") != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reset token has already been used");
        }

        UUID tokenId = (UUID) tokenRow.get("id");
        UUID customerId = (UUID) tokenRow.get("customer_id");

        String passwordHash = encoder.encode(newPassword);
        db.update("UPDATE customer_users SET password_hash = ?, updated_at = ? WHERE id = ?",
            passwordHash, java.sql.Timestamp.from(Instant.now()), customerId);

        tokenRepository.markUsed(tokenId);
        tokenRepository.invalidateAllForCustomer(customerId);

        try {
            db.update("DELETE FROM auth_sessions WHERE user_id = ?", customerId);
        } catch (Exception e) {
            log.debug("Could not invalidate auth_sessions for customer {}: {}", customerId, e.getMessage());
        }

        log.info("Password reset completed for customer: {}", customerId);
    }

    private String generateToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String hashToken(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm not available", e);
        }
    }
}
