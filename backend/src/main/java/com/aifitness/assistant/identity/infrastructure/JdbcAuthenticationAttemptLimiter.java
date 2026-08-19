package com.aifitness.assistant.identity.infrastructure;

import com.aifitness.assistant.identity.application.AuthenticationAttemptLimiter;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import javax.sql.DataSource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** Shared credential-digest plus global fixed-window limiter for pre-authentication endpoints. */
final class JdbcAuthenticationAttemptLimiter implements AuthenticationAttemptLimiter {
    private static final String UPDATE = """
            UPDATE authentication_rate_limit_bucket
            SET attempts = attempts + 1, expires_at = ?
            WHERE action = ? AND key_digest = ? AND bucket_start = ? AND attempts < ?
            """;
    private static final String INSERT = """
            INSERT INTO authentication_rate_limit_bucket
                (action, key_digest, bucket_start, attempts, expires_at)
            VALUES (?, ?, ?, 1, ?)
            """;
    private static final String CLEANUP = """
            DELETE FROM authentication_rate_limit_bucket WHERE expires_at <= ? LIMIT 128
            """;

    private final JdbcOperations jdbc;
    private final int maximumPerCredential;
    private final int maximumGlobal;
    private final long windowMillis;

    JdbcAuthenticationAttemptLimiter(
            DataSource dataSource,
            int maximumPerCredential,
            int maximumGlobal,
            Duration window) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                maximumPerCredential, maximumGlobal, window);
    }

    JdbcAuthenticationAttemptLimiter(
            JdbcOperations jdbc,
            int maximumPerCredential,
            int maximumGlobal,
            Duration window) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        Objects.requireNonNull(window, "window must not be null");
        if (maximumPerCredential < 1 || maximumGlobal < maximumPerCredential
                || window.isNegative() || window.isZero() || window.toMillis() < 1) {
            throw new IllegalArgumentException("invalid authentication rate limit");
        }
        this.maximumPerCredential = maximumPerCredential;
        this.maximumGlobal = maximumGlobal;
        this.windowMillis = window.toMillis();
    }

    @Override
    public boolean allow(Action action, String credential, Instant now) {
        Objects.requireNonNull(action, "action must not be null");
        Objects.requireNonNull(credential, "credential must not be null");
        Objects.requireNonNull(now, "now must not be null");
        Instant bucketStart = Instant.ofEpochMilli(
                Math.floorDiv(now.toEpochMilli(), windowMillis) * windowMillis);
        Instant expiresAt = bucketStart.plusMillis(windowMillis);
        jdbc.update(CLEANUP, Timestamp.from(now));
        if (!claim(action, digest("GLOBAL:" + action.name()), bucketStart, expiresAt, maximumGlobal)) {
            return false;
        }
        return claim(action, digest(credential), bucketStart, expiresAt, maximumPerCredential);
    }

    private boolean claim(
            Action action, byte[] keyDigest, Instant bucketStart, Instant expiresAt, int maximum) {
        if (jdbc.update(UPDATE, Timestamp.from(expiresAt), action.name(), keyDigest,
                Timestamp.from(bucketStart), maximum) == 1) {
            return true;
        }
        try {
            return jdbc.update(INSERT, action.name(), keyDigest,
                    Timestamp.from(bucketStart), Timestamp.from(expiresAt)) == 1;
        } catch (DuplicateKeyException concurrentCreate) {
            return jdbc.update(UPDATE, Timestamp.from(expiresAt), action.name(), keyDigest,
                    Timestamp.from(bucketStart), maximum) == 1;
        }
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
