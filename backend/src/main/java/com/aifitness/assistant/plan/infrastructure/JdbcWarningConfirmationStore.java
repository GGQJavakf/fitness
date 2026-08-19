package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.WarningConfirmationStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** Digest-only, atomic warning confirmation store shared by all application instances. */
final class JdbcWarningConfirmationStore implements WarningConfirmationStore {
    private static final String INSERT = """
            INSERT INTO plan_warning_confirmation
                (token_digest, user_id, fingerprint_digest, issued_at, expires_at, consumed_at)
            VALUES (?, ?, ?, ?, ?, NULL)
            """;
    private static final String CONSUME = """
            UPDATE plan_warning_confirmation
            SET consumed_at = ?
            WHERE token_digest = ? AND user_id = ? AND fingerprint_digest = ?
              AND consumed_at IS NULL AND issued_at <= ? AND expires_at > ?
            """;
    private static final String CLEANUP = """
            DELETE FROM plan_warning_confirmation
            WHERE expires_at <= ? OR consumed_at IS NOT NULL
            LIMIT 128
            """;

    private final JdbcOperations jdbc;
    private final Clock clock;
    private final SecureRandom random;

    JdbcWarningConfirmationStore(DataSource dataSource, Clock clock) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                clock, new SecureRandom());
    }

    JdbcWarningConfirmationStore(JdbcOperations jdbc, Clock clock, SecureRandom random) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String issue(AuthenticatedUserId user, String fingerprint, Instant expiresAt) {
        Objects.requireNonNull(user, "user must not be null");
        requireFingerprint(fingerprint);
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        Instant issuedAt = clock.instant();
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be in the future");
        String token = randomToken();
        int inserted = jdbc.update(INSERT, digest(token), bytes(user.value()), digest(fingerprint),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        if (inserted != 1) throw new IllegalStateException("unable to persist warning confirmation");
        cleanup(issuedAt);
        return token;
    }

    @Override
    public boolean consume(AuthenticatedUserId user, String token, String fingerprint, Instant now) {
        Objects.requireNonNull(user, "user must not be null");
        requireFingerprint(fingerprint);
        Objects.requireNonNull(now, "now must not be null");
        if (token == null || token.isBlank() || token.length() > 256) return false;
        int consumed = jdbc.update(CONSUME, Timestamp.from(now), digest(token), bytes(user.value()),
                digest(fingerprint), Timestamp.from(now), Timestamp.from(now));
        cleanup(now);
        return consumed == 1;
    }

    private void cleanup(Instant now) {
        jdbc.update(CLEANUP, Timestamp.from(now));
    }

    private String randomToken() {
        byte[] value = new byte[32];
        random.nextBytes(value);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static void requireFingerprint(String fingerprint) {
        if (fingerprint == null || fingerprint.isBlank() || fingerprint.length() > 128) {
            throw new IllegalArgumentException("fingerprint is invalid");
        }
    }
}
