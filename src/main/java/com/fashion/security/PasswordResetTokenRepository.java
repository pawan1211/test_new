package com.fashion.security;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Repository
public class PasswordResetTokenRepository {

    private final JdbcTemplate db;

    public PasswordResetTokenRepository(JdbcTemplate db) {
        this.db = db;
    }

    public void insert(UUID id, UUID customerId, String tokenHash, Instant expiresAt) {
        db.update(
            "INSERT INTO password_reset_tokens(id, customer_id, token_hash, expires_at) VALUES(?,?,?,?)",
            id, customerId, tokenHash, Timestamp.from(expiresAt)
        );
    }

    public Map<String, Object> findByTokenHash(String tokenHash) {
        List<Map<String, Object>> rows = db.queryForList(
            "SELECT id, customer_id, token_hash, expires_at, used_at, created_at " +
            "FROM password_reset_tokens WHERE token_hash = ?",
            tokenHash
        );
        return rows.isEmpty() ? null : rows.get(0);
    }

    public int markUsed(UUID id) {
        return db.update(
            "UPDATE password_reset_tokens SET used_at = ? WHERE id = ?",
            Timestamp.from(Instant.now()), id
        );
    }

    public int invalidateAllForCustomer(UUID customerId) {
        return db.update(
            "UPDATE password_reset_tokens SET used_at = ? " +
            "WHERE customer_id = ? AND used_at IS NULL",
            Timestamp.from(Instant.now()), customerId
        );
    }

    public int deleteExpired() {
        return db.update(
            "DELETE FROM password_reset_tokens WHERE expires_at < ?",
            Timestamp.from(Instant.now())
        );
    }
}
