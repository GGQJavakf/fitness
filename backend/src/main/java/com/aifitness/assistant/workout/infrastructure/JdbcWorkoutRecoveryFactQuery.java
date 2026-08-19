package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutRecoveryFactQuery;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** One-round-trip query for completed sessions that still contain effective actual work facts. */
public final class JdbcWorkoutRecoveryFactQuery implements WorkoutRecoveryFactQuery {
    private static final String SELECT = """
            SELECT DISTINCT ws.id, ws.completed_at,
                   COALESCE(
                     JSON_UNQUOTE(JSON_EXTRACT(x.replacement_snapshot_json, '$.exerciseCode')),
                     JSON_UNQUOTE(JSON_EXTRACT(x.exercise_snapshot_json, '$.exerciseCode'))
                   ) AS exercise_code,
                   COALESCE(
                     JSON_UNQUOTE(JSON_EXTRACT(x.replacement_snapshot_json, '$.contentVersion')),
                     JSON_UNQUOTE(JSON_EXTRACT(x.exercise_snapshot_json, '$.contentVersion'))
                   ) AS content_version
            FROM workout_session ws
            JOIN workout_exercise_snapshot x ON x.session_id = ws.id
            JOIN workout_set s ON s.session_exercise_id = x.id
            WHERE ws.user_id = ?
              AND ws.status = 'COMPLETED'
              AND ws.completed_at >= ?
              AND s.completion_status = 'COMPLETED'
              AND s.set_type = 'WORK'
              AND NOT EXISTS (
                SELECT 1 FROM workout_set_void v WHERE v.workout_set_id = s.id
              )
            ORDER BY ws.completed_at DESC, exercise_code
            """;

    private final JdbcTemplate jdbc;

    public JdbcWorkoutRecoveryFactQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public List<CompletedExerciseFact> findCompletedExerciseFacts(
            UUID userId, Instant completedAfter) {
        Objects.requireNonNull(userId, "user id must not be null");
        Objects.requireNonNull(completedAfter, "completed after must not be null");
        return jdbc.query(SELECT, (row, ignored) -> new CompletedExerciseFact(
                uuid(row.getBytes("id")),
                row.getTimestamp("completed_at").toInstant(),
                row.getString("exercise_code"),
                row.getString("content_version")),
                bytes(userId), Timestamp.from(completedAfter));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
