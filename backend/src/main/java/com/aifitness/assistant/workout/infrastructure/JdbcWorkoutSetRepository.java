package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcWorkoutSetRepository implements WorkoutSetRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcWorkoutSetRepository(DataSource dataSource, ObjectMapper json) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public SaveResult save(UUID userId, WorkoutSet candidate, long expectedSessionVersion) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            List<SaveResult> existing = jdbc.query("""
                    SELECT s.*, ws.id AS owner_session_id
                    FROM workout_set s
                    JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                    JOIN workout_session ws ON ws.id = x.session_id
                    WHERE ws.id = ? AND ws.user_id = ? AND x.id = ? AND s.client_set_key = ?
                    FOR UPDATE
                    """, (row, index) -> read(row), bytes(candidate.sessionId()), bytes(userId),
                    bytes(candidate.sessionExerciseId()), candidate.clientSetKey());
            if (!existing.isEmpty()) {
                SaveResult persisted = existing.getFirst();
                if (!persisted.set().payloadDigest().equals(candidate.payloadDigest())) {
                    throw new WorkoutSessionService.IdempotencyConflictException();
                }
                return new SaveResult(persisted.set(), persisted.sessionVersion(), true);
            }
            List<SessionRow> sessions = jdbc.query("""
                    SELECT ws.sync_version, ws.status
                    FROM workout_session ws
                    JOIN workout_exercise_snapshot x ON x.session_id = ws.id
                    WHERE ws.id = ? AND ws.user_id = ? AND x.id = ?
                    FOR UPDATE
                    """, (row, index) -> new SessionRow(
                            row.getLong("sync_version"), row.getString("status")),
                    bytes(candidate.sessionId()), bytes(userId), bytes(candidate.sessionExerciseId()));
            if (sessions.size() != 1) {
                throw new WorkoutSessionService.SessionNotFoundException();
            }
            SessionRow session = sessions.getFirst();
            if (session.version() != expectedSessionVersion) {
                throw new WorkoutSessionService.VersionConflictException(session.version());
            }
            if ("COMPLETED".equals(session.status()) || "ABORTED".equals(session.status())) {
                throw new WorkoutSetService.SessionNotAcceptingSetsException();
            }
            if (!"IN_PROGRESS".equals(session.status()) && !"PAUSED".equals(session.status())) {
                throw new IllegalStateException("workout session does not accept set entries");
            }
            long appliedVersion = expectedSessionVersion + 1;
            int updated = jdbc.update("""
                    UPDATE workout_session SET sync_version = ?
                    WHERE id = ? AND user_id = ? AND sync_version = ?
                    """, appliedVersion, bytes(candidate.sessionId()), bytes(userId), expectedSessionVersion);
            if (updated != 1) {
                throw new WorkoutSessionService.VersionConflictException(currentVersion(candidate.sessionId(), userId));
            }
            jdbc.update("""
                    INSERT INTO workout_set
                        (id, session_exercise_id, client_set_key, client_operation_seq,
                         set_type, set_order, target_json, actual_weight, unit, actual_reps,
                         remaining_reps, completion_status, completed_at, server_revision,
                         anomaly_status, payload_digest, applied_session_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, bytes(candidate.id()), bytes(candidate.sessionExerciseId()), candidate.clientSetKey(),
                    candidate.clientOperationSeq(), candidate.setType().name(), candidate.setOrder(),
                    targetJson(candidate.target()), candidate.actual().weight(), candidate.actual().unit(),
                    candidate.actual().reps(), candidate.remainingReps(), candidate.completionStatus().name(),
                    candidate.completedAt().map(Timestamp::from).orElse(null), candidate.serverRevision(),
                    candidate.anomalyStatus().map(Enum::name).orElse(null),
                    HexFormat.of().parseHex(candidate.payloadDigest()), appliedVersion);
            return new SaveResult(candidate, appliedVersion, false);
        }));
    }

    @Override
    public Optional<WorkoutSet> find(
            UUID userId, UUID sessionId, UUID sessionExerciseId, String clientSetKey) {
        return jdbc.query("""
                SELECT s.*, ws.id AS owner_session_id
                FROM workout_set s
                JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                JOIN workout_session ws ON ws.id = x.session_id
                WHERE ws.id = ? AND ws.user_id = ? AND x.id = ? AND s.client_set_key = ?
                """, (row, index) -> read(row).set(), bytes(sessionId), bytes(userId),
                bytes(sessionExerciseId), clientSetKey).stream().findFirst();
    }

    @Override
    public List<WorkoutSet> findBySession(UUID userId, UUID sessionId) {
        return jdbc.query("""
                SELECT s.*, ws.id AS owner_session_id
                FROM workout_set s
                JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                JOIN workout_session ws ON ws.id = x.session_id
                WHERE ws.id = ? AND ws.user_id = ?
                ORDER BY x.exercise_order, s.set_order, s.client_operation_seq
                """, (row, index) -> read(row).set(), bytes(sessionId), bytes(userId));
    }

    private long currentVersion(UUID sessionId, UUID userId) {
        Long version = jdbc.queryForObject(
                "SELECT sync_version FROM workout_session WHERE id = ? AND user_id = ?",
                Long.class, bytes(sessionId), bytes(userId));
        if (version == null) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        return version;
    }

    private SaveResult read(ResultSet row) throws SQLException {
        Map<String, Object> target = readJson(row.getString("target_json"));
        Timestamp completedAt = row.getTimestamp("completed_at");
        String anomaly = row.getString("anomaly_status");
        WorkoutSet set = new WorkoutSet(
                uuid(row.getBytes("id")), uuid(row.getBytes("owner_session_id")),
                uuid(row.getBytes("session_exercise_id")), row.getString("client_set_key"),
                row.getLong("client_operation_seq"), WorkoutSet.SetType.valueOf(row.getString("set_type")),
                row.getInt("set_order"),
                new WorkoutSet.Performance(decimal(target, "weight"), text(target, "unit"), number(target, "reps")),
                new WorkoutSet.Performance(row.getBigDecimal("actual_weight"), row.getString("unit"),
                        nullableInteger(row, "actual_reps")),
                nullableInteger(row, "remaining_reps"),
                WorkoutSet.CompletionStatus.valueOf(row.getString("completion_status")),
                Optional.ofNullable(completedAt).map(Timestamp::toInstant), row.getLong("server_revision"),
                anomaly == null ? Optional.empty() : Optional.of(WorkoutSet.AnomalyStatus.valueOf(anomaly)),
                HexFormat.of().formatHex(row.getBytes("payload_digest")));
        return new SaveResult(set, row.getLong("applied_session_version"), false);
    }

    private String targetJson(WorkoutSet.Performance target) {
        try {
            return json.writeValueAsString(Map.of(
                    "weight", target.weight(), "unit", target.unit(), "reps", target.reps()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("workout set target cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted workout set target is invalid", exception);
        }
    }

    private static String text(Map<String, Object> values, String field) {
        return String.valueOf(Objects.requireNonNull(values.get(field), "persisted target field is missing"));
    }

    private static int number(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Number number)) throw new IllegalStateException("persisted target number is missing");
        return number.intValue();
    }

    private static Integer nullableInteger(ResultSet row, String field) throws SQLException {
        Object value = row.getObject(field);
        if (value == null) return null;
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("persisted workout set number is invalid");
        }
        return number.intValue();
    }

    private static BigDecimal decimal(Map<String, Object> values, String field) {
        return new BigDecimal(text(values, field));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record SessionRow(long version, String status) {}
}
