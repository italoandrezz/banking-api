package com.italo.bankingapi.repository;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class IdempotencyRepository {
    private final JdbcTemplate jdbc;

    public boolean claim(UUID customerId, String key, String fingerprint) {
        // The unique constraint waits for an in-flight owner to commit or roll back.
        return jdbc.update("""
                INSERT INTO financial_idempotency (customer_id, idempotency_key, request_fingerprint)
                VALUES (?, ?, ?) ON CONFLICT (customer_id, idempotency_key) DO NOTHING
                """, customerId, key, fingerprint) == 1;
    }

    public StoredResult find(UUID customerId, String key) {
        return jdbc.queryForObject("""
                SELECT request_fingerprint, response_json FROM financial_idempotency
                WHERE customer_id = ? AND idempotency_key = ?
                """, (rs, row) -> new StoredResult(rs.getString(1), rs.getString(2)), customerId, key);
    }

    public void complete(UUID customerId, String key, String response) {
        jdbc.update("""
                UPDATE financial_idempotency SET response_json = ?
                WHERE customer_id = ? AND idempotency_key = ?
                """, response, customerId, key);
    }

    public record StoredResult(String fingerprint, String response) {}
}
