package com.fashion.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PasswordResetServiceTest {

    private JdbcTemplate db;
    private PasswordResetTokenRepository tokenRepository;
    private EmailService emailService;
    private PasswordResetRateLimiter rateLimiter;
    private PasswordResetService service;
    private BCryptPasswordEncoder encoder;

    @BeforeEach
    void setUp() {
        db = mock(JdbcTemplate.class);
        tokenRepository = mock(PasswordResetTokenRepository.class);
        emailService = mock(EmailService.class);
        rateLimiter = new PasswordResetRateLimiter();
        service = new PasswordResetService(db, tokenRepository, emailService, rateLimiter);
        encoder = new BCryptPasswordEncoder();
    }

    @Test
    void requestPasswordResetWithUnknownEmailReturnsGenericSuccess() {
        when(db.queryForList(anyString(), anyString())).thenReturn(Collections.emptyList());
        assertDoesNotThrow(() -> service.requestPasswordReset("unknown@example.com"));
        verify(tokenRepository, never()).insert(any(), any(), any(), any());
        verify(emailService, never()).sendPasswordResetEmail(anyString(), anyString());
    }

    @Test
    void requestPasswordResetWithValidEmailSendsEmail() {
        UUID customerId = UUID.randomUUID();
        Map<String, Object> customer = new HashMap<>();
        customer.put("id", customerId);
        customer.put("email", "customer@example.com");
        when(db.queryForList(anyString(), anyString())).thenReturn(List.of(customer));
        when(emailService.isConfigured()).thenReturn(true);

        service.requestPasswordReset("customer@example.com");

        verify(tokenRepository).insert(any(), eq(customerId), anyString(), any(Instant.class));
        verify(emailService).sendPasswordResetEmail(eq("customer@example.com"), contains("/reset-password?token="));
    }

    @Test
    void requestPasswordResetWithInvalidEmailThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.requestPasswordReset("not-an-email"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void requestPasswordResetWithNullEmailThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.requestPasswordReset(null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void resetPasswordWithValidTokenSucceeds() {
        UUID tokenId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String rawToken = "valid-token-123";
        String tokenHash = hashToken(rawToken);

        Map<String, Object> tokenRow = new HashMap<>();
        tokenRow.put("id", tokenId);
        tokenRow.put("customer_id", customerId);
        tokenRow.put("token_hash", tokenHash);
        tokenRow.put("expires_at", Timestamp.from(Instant.now().plusSeconds(900)));
        tokenRow.put("used_at", null);

        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(tokenRow);
        when(db.update(anyString(), any(), any(), any())).thenReturn(1);

        assertDoesNotThrow(() -> service.resetPassword(rawToken, "NewSecurePassword123!", "NewSecurePassword123!"));

        verify(db).update(contains("UPDATE customer_users"), any(), any(), any());
        verify(tokenRepository).markUsed(tokenId);
        verify(tokenRepository).invalidateAllForCustomer(customerId);
    }

    @Test
    void resetPasswordWithInvalidTokenThrowsBadRequest() {
        String rawToken = "invalid-token";
        String tokenHash = hashToken(rawToken);
        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(null);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword(rawToken, "NewSecurePassword123!", "NewSecurePassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("Invalid") || ex.getReason().contains("expired"));
    }

    @Test
    void resetPasswordWithExpiredTokenThrowsBadRequest() {
        UUID tokenId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String rawToken = "expired-token";
        String tokenHash = hashToken(rawToken);

        Map<String, Object> tokenRow = new HashMap<>();
        tokenRow.put("id", tokenId);
        tokenRow.put("customer_id", customerId);
        tokenRow.put("token_hash", tokenHash);
        tokenRow.put("expires_at", Timestamp.from(Instant.now().minusSeconds(3600)));
        tokenRow.put("used_at", null);

        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(tokenRow);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword(rawToken, "NewSecurePassword123!", "NewSecurePassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("expired"));
    }

    @Test
    void resetPasswordWithUsedTokenThrowsBadRequest() {
        UUID tokenId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        String rawToken = "used-token";
        String tokenHash = hashToken(rawToken);

        Map<String, Object> tokenRow = new HashMap<>();
        tokenRow.put("id", tokenId);
        tokenRow.put("customer_id", customerId);
        tokenRow.put("token_hash", tokenHash);
        tokenRow.put("expires_at", Timestamp.from(Instant.now().plusSeconds(900)));
        tokenRow.put("used_at", Timestamp.from(Instant.now().minusSeconds(60)));

        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(tokenRow);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword(rawToken, "NewSecurePassword123!", "NewSecurePassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("already been used"));
    }

    @Test
    void resetPasswordWithMismatchedPasswordsThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword("some-token", "NewSecurePassword123!", "DifferentPassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("do not match"));
    }

    @Test
    void resetPasswordWithWeakPasswordThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword("some-token", "short", "short"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
        assertTrue(ex.getReason().contains("at least"));
    }

    @Test
    void resetPasswordWithNullTokenThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword(null, "NewSecurePassword123!", "NewSecurePassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void resetPasswordWithBlankTokenThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword("  ", "NewSecurePassword123!", "NewSecurePassword123!"));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void resetPasswordWithNullPasswordThrowsBadRequest() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword("some-token", null, null));
        assertEquals(HttpStatus.BAD_REQUEST, ex.getStatusCode());
    }

    @Test
    void rateLimiterBlocksExcessiveForgotPasswordRequests() {
        UUID customerId = UUID.randomUUID();
        Map<String, Object> customer = new HashMap<>();
        customer.put("id", customerId);
        customer.put("email", "customer@example.com");
        when(db.queryForList(anyString(), anyString())).thenReturn(List.of(customer));

        for (int i = 0; i < 3; i++) {
            final int attempt = i;
            assertDoesNotThrow(() -> service.requestPasswordReset("customer@example.com"),
                "Attempt " + attempt + " should succeed");
        }

        assertThrows(ResponseStatusException.class,
            () -> service.requestPasswordReset("customer@example.com"),
            "4th attempt should be rate limited");
    }

    @Test
    void rateLimiterBlocksExcessiveResetPasswordAttempts() {
        String rawToken = "brute-force-token";
        String tokenHash = hashToken(rawToken);
        when(tokenRepository.findByTokenHash(tokenHash)).thenReturn(null);

        for (int i = 0; i < 5; i++) {
            final int attempt = i;
            assertThrows(ResponseStatusException.class,
                () -> service.resetPassword(rawToken, "NewSecurePassword123!", "NewSecurePassword123!"),
                "Attempt " + attempt + " should throw (invalid token)");
        }

        ResponseStatusException rateLimitEx = assertThrows(ResponseStatusException.class,
            () -> service.resetPassword(rawToken, "NewSecurePassword123!", "NewSecurePassword123!"),
            "6th attempt should be rate limited");
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, rateLimitEx.getStatusCode());
    }

    private String hashToken(String token) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
