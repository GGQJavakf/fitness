package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRateLimitPort;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared fixed-window limiter with bounded atomic increments across service instances. */
final class JdbcPrivacyRateLimiter implements PrivacyRateLimitPort {

    private static final String REMOVE_EXPIRED = """
            DELETE FROM privacy_rate_limit_bucket WHERE expires_at <= ? LIMIT 128
            """;
    private static final String INCREMENT_EXISTING = """
            UPDATE privacy_rate_limit_bucket
            SET attempts = attempts + 1, expires_at = ?
            WHERE user_id = ? AND action = ? AND bucket_start = ? AND attempts < ?
            """;
    private static final String INSERT_BUCKET = """
            INSERT INTO privacy_rate_limit_bucket
                (user_id, action, bucket_start, attempts, expires_at)
            VALUES (?, ?, ?, 1, ?)
            """;

    private final JdbcOperations jdbc;
    private final int maximumAttempts;
    private final long windowMillis;

    JdbcPrivacyRateLimiter(DataSource dataSource, int maximumAttempts, Duration window) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                maximumAttempts, window);
    }

    JdbcPrivacyRateLimiter(JdbcOperations jdbc, int maximumAttempts, Duration window) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(window, "window must not be null");
        if (maximumAttempts < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("invalid privacy rate limit");
        }
        this.maximumAttempts = maximumAttempts;
        this.windowMillis = window.toMillis();
        if (windowMillis < 1) {
            throw new IllegalArgumentException("privacy rate-limit window must be at least 1 ms");
        }
    }

    @Override
    public boolean allow(UUID userId, Action action, Instant now) {
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Instant bucketStart = Instant.ofEpochMilli(
                Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis);
        Instant expiresAt = bucketStart.plusMillis(windowMillis);
        removeExpired(now);
        if (incrementExisting(userId, action, bucketStart, expiresAt) == 1) {
            return true;
        }
        try {
            return jdbc.update(INSERT_BUCKET, bytes(userId), action.name(),
                    Timestamp.from(bucketStart), Timestamp.from(expiresAt)) == 1;
        } catch (DuplicateKeyException concurrentCreate) {
            return incrementExisting(userId, action, bucketStart, expiresAt) == 1;
        }
    }

    private int incrementExisting(
            UUID userId, Action action, Instant bucketStart, Instant expiresAt) {
        return jdbc.update(INCREMENT_EXISTING, Timestamp.from(expiresAt), bytes(userId),
                action.name(), Timestamp.from(bucketStart), maximumAttempts);
    }

    private void removeExpired(Instant now) {
        jdbc.update(REMOVE_EXPIRED, Timestamp.from(now));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
