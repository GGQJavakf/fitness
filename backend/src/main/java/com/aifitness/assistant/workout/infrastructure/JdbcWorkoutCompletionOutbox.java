package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutCompletionOutbox;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL outbox with lease claims and claim-token guarded acknowledgements. */
public final class JdbcWorkoutCompletionOutbox implements WorkoutCompletionOutbox {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcWorkoutCompletionOutbox(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
    }

    @Override
    public void appendIfAbsent(CompletionEvent event) {
        jdbc.update("""
                INSERT INTO workout_completion_outbox
                    (id, user_id, session_id, event_type, payload_json, status, attempt_count,
                     next_attempt_at, created_at, updated_at)
                VALUES (?, ?, ?, 'WORKOUT_COMPLETED', JSON_OBJECT('sessionId', ?, 'userId', ?),
                        'PENDING', 0, ?, ?, ?)
                ON DUPLICATE KEY UPDATE id = id
                """, bytes(event.id()), bytes(event.userId()), bytes(event.sessionId()),
                event.sessionId().toString(), event.userId().toString(), Timestamp.from(event.createdAt()),
                Timestamp.from(event.createdAt()), Timestamp.from(event.createdAt()));
    }

    @Override
    public Optional<ClaimedEvent> claimNext(Instant now, Instant claimedUntil) {
        return Optional.ofNullable(transactions.execute(ignored -> {
            Optional<UUID> id = jdbc.query("""
                    SELECT id
                    FROM workout_completion_outbox
                    WHERE (status = 'PENDING' AND next_attempt_at <= ?)
                       OR (status = 'PROCESSING' AND claimed_until <= ?)
                    ORDER BY created_at, id
                    LIMIT 1 FOR UPDATE SKIP LOCKED
                    """, (row, index) -> uuid(row.getBytes("id")), Timestamp.from(now), Timestamp.from(now))
                    .stream().findFirst();
            if (id.isEmpty()) return Optional.<ClaimedEvent>empty();
            UUID claimToken = UUID.randomUUID();
            jdbc.update("""
                    UPDATE workout_completion_outbox
                    SET status = 'PROCESSING', claimed_until = ?, claim_token = ?,
                        attempt_count = attempt_count + 1, updated_at = ?
                    WHERE id = ?
                    """, Timestamp.from(claimedUntil), bytes(claimToken), Timestamp.from(now), bytes(id.orElseThrow()));
            return jdbc.query("""
                    SELECT id, user_id, session_id, attempt_count
                    FROM workout_completion_outbox WHERE id = ?
                    """, (row, index) -> new ClaimedEvent(
                            uuid(row.getBytes("id")), uuid(row.getBytes("user_id")),
                            uuid(row.getBytes("session_id")), claimToken, row.getInt("attempt_count")),
                    bytes(id.orElseThrow())).stream().findFirst();
        })).orElseGet(Optional::empty);
    }

    @Override
    public void markProcessed(UUID eventId, UUID claimToken, Instant processedAt) {
        int updated = jdbc.update("""
                UPDATE workout_completion_outbox
                SET status = 'PROCESSED', processed_at = ?, claimed_until = NULL, claim_token = NULL,
                    last_error = NULL, updated_at = ?
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, Timestamp.from(processedAt), Timestamp.from(processedAt), bytes(eventId), bytes(claimToken));
        if (updated != 1) throw new IllegalStateException("completion outbox claim is stale");
    }

    @Override
    public void release(UUID eventId, UUID claimToken, Instant nextAttemptAt, String redactedError) {
        String safeError = redactedError == null ? "RuntimeException"
                : redactedError.substring(0, Math.min(redactedError.length(), 128));
        int updated = jdbc.update("""
                UPDATE workout_completion_outbox
                SET status = 'PENDING', next_attempt_at = ?, claimed_until = NULL, claim_token = NULL,
                    last_error = ?, updated_at = UTC_TIMESTAMP(6)
                WHERE id = ? AND status = 'PROCESSING' AND claim_token = ?
                """, Timestamp.from(nextAttemptAt), safeError, bytes(eventId), bytes(claimToken));
        if (updated != 1) throw new IllegalStateException("completion outbox claim is stale");
    }

    @Override
    public <T> T inTransaction(Supplier<T> operation) {
        return transactions.execute(ignored -> operation.get());
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
