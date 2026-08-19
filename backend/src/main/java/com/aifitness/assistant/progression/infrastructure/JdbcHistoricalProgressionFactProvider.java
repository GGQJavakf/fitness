package com.aifitness.assistant.progression.infrastructure;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.progression.application.EffectiveSetSelector;
import com.aifitness.assistant.progression.domain.ProgressionInput;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL history projection normalized to the current stable exercise identity for deterministic replay. */
public final class JdbcHistoricalProgressionFactProvider
        implements CompletedWorkoutProgressionObserver.HistoricalFactProvider {
    private static final int MAX_FACTS = 300;
    private final JdbcTemplate jdbc;

    public JdbcHistoricalProgressionFactProvider(DataSource dataSource) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource));
    }

    @Override
    public List<EffectiveSetSelector.RawSetFact> facts(
            AuthenticatedUserId user,
            WorkoutExerciseSnapshot exercise,
            List<EffectiveSetSelector.RawSetFact> currentFacts) {
        List<EffectiveSetSelector.RawSetFact> stored = jdbc.query("""
                SELECT wset.id AS fact_id, ws.id AS session_id, wset.set_type, wset.set_order,
                       wset.unit, wset.completion_status, wset.actual_weight, wset.actual_reps,
                       wset.remaining_reps, wset.safety_flag, wset.anomaly_status,
                       wset.completed_at, ws.completed_at AS session_completed_at,
                       wset.server_revision,
                       COALESCE(LOWER(HEX(wset.payload_digest)),
                                SHA2(CONCAT(HEX(wset.id), ':', wset.server_revision), 256)) AS payload_digest
                FROM workout_session ws
                JOIN workout_exercise_snapshot wes ON wes.session_id = ws.id
                JOIN workout_set wset ON wset.session_exercise_id = wes.id
                WHERE ws.user_id = ?
                  AND ws.status = 'COMPLETED'
                  AND NOT EXISTS (
                      SELECT 1 FROM workout_set_void wv WHERE wv.workout_set_id = wset.id
                  )
                  AND COALESCE(
                        JSON_UNQUOTE(JSON_EXTRACT(wes.replacement_snapshot_json, '$.exerciseCode')),
                        JSON_UNQUOTE(JSON_EXTRACT(wes.exercise_snapshot_json, '$.exerciseCode'))
                      ) = ?
                ORDER BY ws.completed_at DESC, ws.id DESC, wset.set_order ASC, wset.id ASC
                LIMIT ?
                """, (row, ignored) -> new EffectiveSetSelector.RawSetFact(
                uuid(row.getBytes("fact_id")), uuid(row.getBytes("session_id")), user.value(),
                CompletedWorkoutProgressionObserver.stableExerciseId(exercise.exerciseCode()),
                exercise.exerciseCode(), row.getString("unit"),
                EffectiveSetSelector.SetKind.valueOf(row.getString("set_type")), row.getInt("set_order"),
                EffectiveSetSelector.SessionOutcome.COMPLETED, factStatus(row.getString("completion_status")),
                row.getBigDecimal("actual_weight"), row.getObject("actual_reps", Integer.class) == null
                        ? 0 : row.getInt("actual_reps"),
                Optional.ofNullable(row.getObject("remaining_reps", Integer.class)),
                Optional.ofNullable(row.getString("safety_flag"))
                        .map(ProgressionInput.SafetyFlag::valueOf),
                row.getString("anomaly_status") != null, true,
                Optional.ofNullable(row.getTimestamp("completed_at"))
                        .orElseGet(() -> rowTimestamp(row, "session_completed_at")).toInstant(),
                row.getLong("server_revision"), row.getString("payload_digest")),
                bytes(user.value()), exercise.exerciseCode(), MAX_FACTS);
        Map<UUID, EffectiveSetSelector.RawSetFact> merged = new LinkedHashMap<>();
        stored.forEach(value -> merged.put(value.factId(), value));
        currentFacts.forEach(value -> merged.putIfAbsent(value.factId(), value));
        return List.copyOf(new ArrayList<>(merged.values()));
    }

    private static EffectiveSetSelector.FactStatus factStatus(String value) {
        return switch (value) {
            case "COMPLETED" -> EffectiveSetSelector.FactStatus.COMPLETED;
            case "FAILED" -> EffectiveSetSelector.FactStatus.FAILED;
            case "PLANNED", "SKIPPED" -> EffectiveSetSelector.FactStatus.SKIPPED;
            default -> throw new IllegalStateException("unknown workout fact status");
        };
    }

    private static java.sql.Timestamp rowTimestamp(java.sql.ResultSet row, String field) {
        try {
            return Objects.requireNonNull(row.getTimestamp(field));
        } catch (java.sql.SQLException exception) {
            throw new IllegalStateException("cannot read workout history timestamp", exception);
        }
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
