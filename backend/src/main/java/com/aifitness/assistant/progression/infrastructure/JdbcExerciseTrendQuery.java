package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.ExerciseTrendQuery;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL read projection; eligibility stays server-side and is never delegated to the client. */
public final class JdbcExerciseTrendQuery implements ExerciseTrendQuery {
    private static final int MAX_POINTS = 50;
    private final JdbcTemplate jdbc;

    public JdbcExerciseTrendQuery(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
    }

    @Override
    public Trend load(AuthenticatedUserId user, String exerciseCode) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        if (exerciseCode == null || exerciseCode.isBlank()) {
            throw new IllegalArgumentException("exercise code must not be blank");
        }
        List<Point> newestFirst = jdbc.query("""
                SELECT ws.id AS session_id, ws.completed_at,
                       MAX(wset.actual_weight) AS top_weight,
                       SUM(wset.actual_reps) AS total_reps,
                       COUNT(*) AS work_set_count
                FROM workout_session ws
                JOIN workout_exercise_snapshot wes ON wes.session_id = ws.id
                JOIN workout_set wset ON wset.session_exercise_id = wes.id
                WHERE ws.user_id = ?
                  AND ws.status = 'COMPLETED'
                  AND COALESCE(
                        JSON_UNQUOTE(JSON_EXTRACT(wes.replacement_snapshot_json, '$.exerciseCode')),
                        JSON_UNQUOTE(JSON_EXTRACT(wes.exercise_snapshot_json, '$.exerciseCode'))
                      ) = ?
                  AND wset.set_type = 'WORK'
                  AND wset.completion_status = 'COMPLETED'
                  AND wset.actual_weight IS NOT NULL
                  AND wset.actual_reps IS NOT NULL
                  AND wset.unit = 'KG'
                  AND wset.anomaly_status IS NULL
                GROUP BY ws.id, ws.completed_at
                ORDER BY ws.completed_at DESC, ws.id DESC
                LIMIT ?
                """, (row, ignored) -> new Point(
                uuid(row.getBytes("session_id")), row.getTimestamp("completed_at").toInstant(),
                row.getBigDecimal("top_weight"), row.getInt("total_reps"), row.getInt("work_set_count")),
                bytes(user.value()), exerciseCode, MAX_POINTS);
        List<Point> chronological = new ArrayList<>(newestFirst);
        Collections.reverse(chronological);
        return new Trend(exerciseCode, "KG", chronological);
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
