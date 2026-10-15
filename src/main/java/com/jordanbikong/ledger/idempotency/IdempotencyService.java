package com.jordanbikong.ledger.idempotency;

import java.util.List;
import java.util.function.Supplier;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * Makes a write safe to retry. The key, the work and the stored response all
 * commit in one transaction, so there is never a saved key without its entry,
 * or an entry without its key. A request that fails rolls its key back too,
 * so the client can fix the request and retry with the same key.
 */
@Service
public class IdempotencyService {

    public static final String HEADER = "Idempotency-Key";
    static final int MAX_KEY_LENGTH = 255;

    private final JdbcTemplate jdbc;
    private final JsonMapper json;

    public IdempotencyService(JdbcTemplate jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    /** The response to send, and whether it was replayed from an earlier request. */
    public record Outcome<T>(T body, boolean replayed) {
    }

    @Transactional
    public <T> Outcome<T> execute(String key, String fingerprint, Class<T> responseType, Supplier<T> work) {
        requireValid(key);

        // If another transaction holds this key uncommitted, Postgres makes
        // this INSERT wait until that transaction commits or rolls back.
        boolean claimed = jdbc.update("""
                INSERT INTO idempotency_records (key, request_hash) VALUES (?, ?)
                ON CONFLICT (key) DO NOTHING
                """, key, fingerprint) == 1;

        if (!claimed) {
            return replay(key, fingerprint, responseType);
        }

        T response = work.get();
        jdbc.update("""
                UPDATE idempotency_records
                   SET response_status = ?, response_body = ?::jsonb
                 WHERE key = ?
                """, HttpStatus.CREATED.value(), json.writeValueAsString(response), key);
        return new Outcome<>(response, false);
    }

    private <T> Outcome<T> replay(String key, String fingerprint, Class<T> responseType) {
        List<StoredRecord> rows = jdbc.query(
                "SELECT request_hash, response_body FROM idempotency_records WHERE key = ?",
                (rs, n) -> new StoredRecord(rs.getString("request_hash"), rs.getString("response_body")),
                key);
        StoredRecord stored = rows.getFirst();
        if (!stored.requestHash().equals(fingerprint)) {
            throw new IdempotencyKeyMismatchException();
        }
        return new Outcome<>(json.readValue(stored.responseBody(), responseType), true);
    }

    private static void requireValid(String key) {
        if (key == null || key.isBlank() || key.length() > MAX_KEY_LENGTH) {
            throw new InvalidIdempotencyKeyException();
        }
    }

    private record StoredRecord(String requestHash, String responseBody) {
    }
}
