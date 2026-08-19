package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutHistoryRepository;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Returns one page and all aggregate metrics in a single SQL round trip. */
public final class JdbcWorkoutHistoryRepository implements WorkoutHistoryRepository {
    private static final String PAGE_PREFIX = """
            WITH history_page AS (
                SELECT ws.id, ws.training_day_id, ws.status, ws.started_at, ws.completed_at
                FROM workout_session ws
            """;
    private static final String SELECT = """
            ), ranked_sets AS (
                SELECT x.session_id, s.actual_weight, s.actual_reps,
                       ROW_NUMBER() OVER (
                           PARTITION BY x.session_id, s.session_exercise_id, s.set_order
                           ORDER BY s.client_operation_seq, s.id
                       ) AS position_rank
                FROM workout_set s
                JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                JOIN history_page hp ON hp.id = x.session_id
                WHERE s.set_type = 'WORK' AND s.completion_status = 'COMPLETED'
                  AND NOT EXISTS (
                      SELECT 1 FROM workout_set_void v WHERE v.workout_set_id = s.id
                  )
                  AND s.set_order <= CAST(JSON_UNQUOTE(
                      JSON_EXTRACT(x.prescription_snapshot_json, '$.workSets')) AS UNSIGNED)
            ), metrics AS (
                SELECT session_id,
                       COUNT(*) AS completed_work_sets,
                       SUM(actual_weight * actual_reps) AS completed_volume_kg,
                       SUM(actual_reps) AS completed_reps,
                       MAX(actual_weight > 0) AS uses_external_load
                FROM ranked_sets
                WHERE position_rank = 1
                GROUP BY session_id
            ), snapshot_facts AS (
                SELECT x.session_id,
                       MAX(JSON_UNQUOTE(JSON_EXTRACT(x.exercise_snapshot_json, '$.trainingDayCode')))
                           AS training_day_code
                FROM workout_exercise_snapshot x
                JOIN history_page hp ON hp.id = x.session_id
                GROUP BY x.session_id
            )
            SELECT hp.id, sf.training_day_code, td.name AS training_day_name,
                   hp.status, hp.started_at, hp.completed_at,
                   COALESCE(metrics.completed_work_sets, 0) AS completed_work_sets,
                   COALESCE(metrics.completed_volume_kg, 0) AS completed_volume_kg,
                   COALESCE(metrics.completed_reps, 0) AS completed_reps,
                   COALESCE(metrics.uses_external_load, 0) AS uses_external_load
            FROM history_page hp
            JOIN snapshot_facts sf ON sf.session_id = hp.id
            JOIN training_day td ON td.id = hp.training_day_id
            LEFT JOIN metrics ON metrics.session_id = hp.id
            ORDER BY hp.started_at DESC, hp.id DESC
            """;

    private final JdbcTemplate jdbc;

    public JdbcWorkoutHistoryRepository(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public List<Projection> findHistory(
            UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("history limit must be positive");
        if (beforeStartedAt.isPresent() != beforeId.isPresent()) {
            throw new IllegalArgumentException("history cursor fields must be provided together");
        }
        String pageClause;
        Object[] arguments;
        if (beforeStartedAt.isPresent()) {
            pageClause = """
                    WHERE ws.user_id = ? AND ws.status IN ('COMPLETED', 'ABORTED')
                      AND (ws.started_at < ? OR (ws.started_at = ? AND ws.id < ?))
                    ORDER BY ws.started_at DESC, ws.id DESC LIMIT ?
                    """;
            Timestamp before = Timestamp.from(beforeStartedAt.orElseThrow());
            arguments = new Object[] { bytes(userId), before, before, bytes(beforeId.orElseThrow()), limit };
        } else {
            pageClause = """
                    WHERE ws.user_id = ? AND ws.status IN ('COMPLETED', 'ABORTED')
                    ORDER BY ws.started_at DESC, ws.id DESC LIMIT ?
                    """;
            arguments = new Object[] { bytes(userId), limit };
        }
        return jdbc.query(PAGE_PREFIX + pageClause + SELECT, (row, ignored) -> new Projection(
                uuid(row.getBytes("id")), row.getString("training_day_code"), row.getString("training_day_name"),
                WorkoutStatus.valueOf(row.getString("status")), row.getTimestamp("started_at").toInstant(),
                row.getTimestamp("completed_at").toInstant(), row.getInt("completed_work_sets"),
                row.getBigDecimal("completed_volume_kg").stripTrailingZeros(), row.getInt("completed_reps"),
                row.getBoolean("uses_external_load")), arguments);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
