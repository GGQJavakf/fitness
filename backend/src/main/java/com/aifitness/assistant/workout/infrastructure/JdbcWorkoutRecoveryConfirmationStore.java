package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutRecoveryConfirmationStore;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcOperations;
import org.springframework.jdbc.core.JdbcTemplate;

/** Multi-instance-safe digest-only store; consume is one conditional database update. */
public final class JdbcWorkoutRecoveryConfirmationStore implements WorkoutRecoveryConfirmationStore {
    private static final String INSERT = """
            INSERT INTO workout_recovery_confirmation
                (token_digest, user_id, plan_id, plan_version_no, training_day_code,
                 client_session_key, assessment_fingerprint, issued_at, expires_at, consumed_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NULL)
            """;
    private static final String CONSUME = """
            UPDATE workout_recovery_confirmation
            SET consumed_at = ?
            WHERE token_digest = ? AND user_id = ? AND plan_id = ? AND plan_version_no = ?
              AND training_day_code = ? AND client_session_key = ? AND assessment_fingerprint = ?
              AND consumed_at IS NULL AND issued_at <= ? AND expires_at > ?
            """;
    private static final String CLEANUP = """
            DELETE FROM workout_recovery_confirmation
            WHERE expires_at <= ? OR consumed_at IS NOT NULL
            LIMIT 128
            """;

    private final JdbcOperations jdbc;
    private final SecureRandom random;

    public JdbcWorkoutRecoveryConfirmationStore(DataSource dataSource) {
        this(new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null")),
                new SecureRandom());
    }

    JdbcWorkoutRecoveryConfirmationStore(JdbcOperations jdbc, SecureRandom random) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc must not be null");
        this.random = Objects.requireNonNull(random, "random must not be null");
    }

    @Override
    public String issue(Binding binding, Instant issuedAt, Instant expiresAt) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(issuedAt, "issuedAt must not be null");
        Objects.requireNonNull(expiresAt, "expiresAt must not be null");
        if (!expiresAt.isAfter(issuedAt)) throw new IllegalArgumentException("expiresAt must be in the future");
        String token = randomToken();
        int inserted = jdbc.update(INSERT,
                digest(token), bytes(binding.userId()), bytes(binding.planId()), binding.planVersionNumber(),
                binding.trainingDayCode(), binding.clientSessionKey(), hexBytes(binding.assessmentFingerprint()),
                Timestamp.from(issuedAt), Timestamp.from(expiresAt));
        if (inserted != 1) throw new IllegalStateException("unable to persist recovery confirmation");
        cleanup(issuedAt);
        return token;
    }

    @Override
    public boolean consume(Binding binding, String token, Instant now) {
        Objects.requireNonNull(binding, "binding must not be null");
        Objects.requireNonNull(now, "now must not be null");
        if (token == null || token.isBlank() || token.length() > 256) return false;
        int consumed = jdbc.update(CONSUME,
                Timestamp.from(now), digest(token), bytes(binding.userId()), bytes(binding.planId()),
                binding.planVersionNumber(), binding.trainingDayCode(), binding.clientSessionKey(),
                hexBytes(binding.assessmentFingerprint()), Timestamp.from(now), Timestamp.from(now));
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

    private static byte[] hexBytes(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
