package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSetRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutSetVoid;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
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
                         safety_flag, anomaly_status, payload_digest, applied_session_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, bytes(candidate.id()), bytes(candidate.sessionExerciseId()), candidate.clientSetKey(),
                    candidate.clientOperationSeq(), candidate.setType().name(), candidate.setOrder(),
                    targetJson(candidate.target()), candidate.actual().weight(), candidate.actual().unit(),
                    candidate.actual().reps(), candidate.remainingReps(), candidate.completionStatus().name(),
                    candidate.completedAt().map(Timestamp::from).orElse(null), candidate.serverRevision(),
                    candidate.safetyFlag().map(Enum::name).orElse(null),
                    candidate.anomalyStatus().map(Enum::name).orElse(null),
                    HexFormat.of().parseHex(candidate.payloadDigest()), appliedVersion);
            return new SaveResult(candidate, appliedVersion, false);
        }));
    }

    @Override
    public SaveResult correct(
            UUID userId,
            WorkoutSet candidate,
            long expectedSessionVersion,
            UUID conflictId,
            Instant correctedAt) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            List<CorrectionRow> found = jdbc.query("""
                    SELECT s.*, ws.id AS owner_session_id, ws.sync_version AS current_session_version,
                           ws.status AS owner_session_status
                    FROM workout_set s
                    JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                    JOIN workout_session ws ON ws.id = x.session_id
                    WHERE ws.id = ? AND ws.user_id = ? AND x.id = ? AND s.client_set_key = ?
                    FOR UPDATE
                    """, (row, index) -> new CorrectionRow(
                            read(row).set(), row.getLong("current_session_version"),
                            com.aifitness.assistant.workout.domain.WorkoutStatus.valueOf(
                                    row.getString("owner_session_status"))),
                    bytes(candidate.sessionId()), bytes(userId), bytes(candidate.sessionExerciseId()),
                    candidate.clientSetKey());
            if (found.size() != 1) throw new WorkoutSessionService.SessionNotFoundException();
            CorrectionRow row = found.getFirst();
            WorkoutSet existing = row.set();
            ensureCorrectionKeepsIdentity(existing, candidate);
            if (row.status().terminal()) {
                throw new WorkoutSetService.SessionNotAcceptingSetsException();
            }
            if (existing.payloadDigest().equals(candidate.payloadDigest())) {
                return new SaveResult(existing, row.sessionVersion(), true);
            }
            if (row.sessionVersion() != expectedSessionVersion) {
                throw new WorkoutSessionService.VersionConflictException(row.sessionVersion());
            }

            jdbc.update("""
                    INSERT INTO workout_set_revision
                        (id, workout_set_id, revision_no, before_json, after_json, reason, created_at)
                    VALUES (?, ?, ?, ?, ?, 'SYNC_CONFLICT_KEEP_LOCAL', ?)
                    """, bytes(conflictId), bytes(existing.id()), candidate.serverRevision(),
                    writeJson(revisionPayload(existing)), writeJson(revisionPayload(candidate)),
                    Timestamp.from(correctedAt));

            long appliedVersion = expectedSessionVersion + 1;
            int sessionUpdated = jdbc.update("""
                    UPDATE workout_session SET sync_version = ?
                    WHERE id = ? AND user_id = ? AND sync_version = ?
                    """, appliedVersion, bytes(candidate.sessionId()), bytes(userId), expectedSessionVersion);
            if (sessionUpdated != 1) {
                throw new WorkoutSessionService.VersionConflictException(currentVersion(candidate.sessionId(), userId));
            }
            int setUpdated = jdbc.update("""
                    UPDATE workout_set
                    SET actual_weight = ?, actual_reps = ?, remaining_reps = ?, completion_status = ?,
                        completed_at = ?, server_revision = ?, safety_flag = ?, anomaly_status = ?, payload_digest = ?,
                        applied_session_version = ?
                    WHERE id = ? AND server_revision = ? AND payload_digest = ?
                    """, candidate.actual().weight(), candidate.actual().reps(), candidate.remainingReps(),
                    candidate.completionStatus().name(),
                    candidate.completedAt().map(Timestamp::from).orElse(null), candidate.serverRevision(),
                    candidate.safetyFlag().map(Enum::name).orElse(null),
                    candidate.anomalyStatus().map(Enum::name).orElse(null),
                    HexFormat.of().parseHex(candidate.payloadDigest()), appliedVersion,
                    bytes(existing.id()), existing.serverRevision(),
                    HexFormat.of().parseHex(existing.payloadDigest()));
            if (setUpdated != 1) {
                throw new IllegalStateException("workout set correction lost its locked source fact");
            }
            return new SaveResult(candidate, appliedVersion, false);
        }));
    }

    @Override
    public VoidResult appendVoid(
            UUID userId,
            UUID sessionId,
            UUID setId,
            String idempotencyKey,
            String payloadDigest,
            long expectedSessionVersion,
            UUID voidId,
            Instant voidedAt) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            List<WorkoutSetVoid> idempotent = jdbc.query("""
                    SELECT v.*
                    FROM workout_set_void v
                    WHERE v.user_id = ? AND v.idempotency_key = ?
                    FOR UPDATE
                    """, (row, index) -> readVoid(row), bytes(userId), idempotencyKey);
            if (!idempotent.isEmpty()) {
                WorkoutSetVoid existing = idempotent.getFirst();
                if (!existing.payloadDigest().equals(payloadDigest)) {
                    throw new WorkoutSessionService.IdempotencyConflictException();
                }
                return new VoidResult(existing, existing.appliedSessionVersion(), true);
            }
            List<SessionRow> sessions = jdbc.query("""
                    SELECT ws.sync_version, ws.status
                    FROM workout_session ws
                    JOIN workout_exercise_snapshot x ON x.session_id = ws.id
                    JOIN workout_set s ON s.session_exercise_id = x.id
                    WHERE ws.id = ? AND ws.user_id = ? AND s.id = ?
                    FOR UPDATE
                    """, (row, index) -> new SessionRow(
                            row.getLong("sync_version"), row.getString("status")),
                    bytes(sessionId), bytes(userId), bytes(setId));
            if (sessions.size() != 1) {
                throw new WorkoutSessionService.SessionNotFoundException();
            }
            List<WorkoutSetVoid> existingVoids = jdbc.query("""
                    SELECT v.*
                    FROM workout_set_void v
                    WHERE v.workout_set_id = ?
                    FOR UPDATE
                    """, (row, index) -> readVoid(row), bytes(setId));
            if (!existingVoids.isEmpty()) {
                WorkoutSetVoid existing = existingVoids.getFirst();
                return new VoidResult(existing, existing.appliedSessionVersion(), true);
            }
            SessionRow session = sessions.getFirst();
            if (session.version() != expectedSessionVersion) {
                throw new WorkoutSessionService.VersionConflictException(session.version());
            }
            if ("COMPLETED".equals(session.status()) || "ABORTED".equals(session.status())) {
                throw new WorkoutSetService.SessionNotAcceptingSetsException();
            }
            if (!"IN_PROGRESS".equals(session.status()) && !"PAUSED".equals(session.status())) {
                throw new IllegalStateException("workout session does not accept set voids");
            }
            long appliedVersion = expectedSessionVersion + 1;
            int updated = jdbc.update("""
                    UPDATE workout_session SET sync_version = ?
                    WHERE id = ? AND user_id = ? AND sync_version = ?
                    """, appliedVersion, bytes(sessionId), bytes(userId), expectedSessionVersion);
            if (updated != 1) {
                throw new WorkoutSessionService.VersionConflictException(currentVersion(sessionId, userId));
            }
            WorkoutSetVoid voidFact = new WorkoutSetVoid(
                    voidId, setId, sessionId, userId, idempotencyKey, payloadDigest,
                    WorkoutSetVoid.Reason.USER_REQUESTED, appliedVersion, voidedAt);
            jdbc.update("""
                    INSERT INTO workout_set_void
                        (id, workout_set_id, session_id, user_id, idempotency_key, payload_digest,
                         reason, applied_session_version, voided_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, bytes(voidFact.id()), bytes(voidFact.workoutSetId()), bytes(voidFact.sessionId()),
                    bytes(voidFact.userId()), voidFact.idempotencyKey(),
                    HexFormat.of().parseHex(voidFact.payloadDigest()), voidFact.reason().name(),
                    voidFact.appliedSessionVersion(), Timestamp.from(voidFact.voidedAt()));
            return new VoidResult(voidFact, appliedVersion, false);
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
    public Optional<WorkoutSet> findById(UUID userId, UUID sessionId, UUID setId) {
        return jdbc.query("""
                SELECT s.*, ws.id AS owner_session_id
                FROM workout_set s
                JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                JOIN workout_session ws ON ws.id = x.session_id
                WHERE ws.id = ? AND ws.user_id = ? AND s.id = ?
                """, (row, index) -> read(row).set(), bytes(sessionId), bytes(userId), bytes(setId))
                .stream().findFirst();
    }

    @Override
    public Optional<WorkoutSetVoid> findVoid(UUID userId, UUID sessionId, UUID setId) {
        return jdbc.query("""
                SELECT v.*
                FROM workout_set_void v
                WHERE v.user_id = ? AND v.session_id = ? AND v.workout_set_id = ?
                """, (row, index) -> readVoid(row), bytes(userId), bytes(sessionId), bytes(setId))
                .stream().findFirst();
    }

    @Override
    public List<WorkoutSet> findBySession(UUID userId, UUID sessionId) {
        return jdbc.query("""
                SELECT s.*, ws.id AS owner_session_id
                FROM workout_set s
                JOIN workout_exercise_snapshot x ON x.id = s.session_exercise_id
                JOIN workout_session ws ON ws.id = x.session_id
                WHERE ws.id = ? AND ws.user_id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM workout_set_void v WHERE v.workout_set_id = s.id
                  )
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
        String safety = row.getString("safety_flag");
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
                safety == null ? Optional.empty() : Optional.of(WorkoutSet.SafetyFlag.valueOf(safety)),
                anomaly == null ? Optional.empty() : Optional.of(WorkoutSet.AnomalyStatus.valueOf(anomaly)),
                HexFormat.of().formatHex(row.getBytes("payload_digest")));
        return new SaveResult(set, row.getLong("applied_session_version"), false);
    }

    private WorkoutSetVoid readVoid(ResultSet row) throws SQLException {
        return new WorkoutSetVoid(
                uuid(row.getBytes("id")), uuid(row.getBytes("workout_set_id")),
                uuid(row.getBytes("session_id")), uuid(row.getBytes("user_id")),
                row.getString("idempotency_key"), HexFormat.of().formatHex(row.getBytes("payload_digest")),
                WorkoutSetVoid.Reason.valueOf(row.getString("reason")),
                row.getLong("applied_session_version"), row.getTimestamp("voided_at").toInstant());
    }

    private String targetJson(WorkoutSet.Performance target) {
        try {
            return json.writeValueAsString(Map.of(
                    "weight", target.weight(), "unit", target.unit(), "reps", target.reps()));
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("workout set target cannot be serialized", exception);
        }
    }

    private String writeJson(Map<String, Object> value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("workout set revision cannot be serialized", exception);
        }
    }

    private static Map<String, Object> revisionPayload(WorkoutSet set) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("sessionId", set.sessionId().toString());
        value.put("sessionExerciseId", set.sessionExerciseId().toString());
        value.put("clientSetKey", set.clientSetKey());
        value.put("clientOperationSeq", set.clientOperationSeq());
        value.put("setType", set.setType().name());
        value.put("setOrder", set.setOrder());
        value.put("target", performancePayload(set.target()));
        value.put("actual", performancePayload(set.actual()));
        value.put("remainingReps", set.remainingReps());
        value.put("completionStatus", set.completionStatus().name());
        value.put("completedAt", set.completedAt().map(Instant::toString).orElse(null));
        value.put("serverRevision", set.serverRevision());
        value.put("safetyFlag", set.safetyFlag().map(Enum::name).orElse(null));
        value.put("anomalyStatus", set.anomalyStatus().map(Enum::name).orElse(null));
        value.put("payloadDigest", set.payloadDigest());
        return value;
    }

    private static Map<String, Object> performancePayload(WorkoutSet.Performance performance) {
        return Map.of(
                "weightKg", performance.weight(),
                "unit", performance.unit(),
                "reps", performance.reps());
    }

    private static void ensureCorrectionKeepsIdentity(WorkoutSet existing, WorkoutSet candidate) {
        if (!existing.id().equals(candidate.id())
                || !existing.sessionId().equals(candidate.sessionId())
                || !existing.sessionExerciseId().equals(candidate.sessionExerciseId())
                || !existing.clientSetKey().equals(candidate.clientSetKey())
                || existing.clientOperationSeq() != candidate.clientOperationSeq()
                || existing.setType() != candidate.setType()
                || existing.setOrder() != candidate.setOrder()
                || !existing.target().equals(candidate.target())
                || candidate.serverRevision() != existing.serverRevision() + 1) {
            throw new IllegalArgumentException("workout set correction cannot change immutable identity fields");
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
    private record CorrectionRow(
            WorkoutSet set,
            long sessionVersion,
            com.aifitness.assistant.workout.domain.WorkoutStatus status) {}
}
