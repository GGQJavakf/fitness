package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSessionRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.domain.WorkoutWarmupPrescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.time.Instant;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Creates a workout and all immutable exercise facts in one MySQL transaction. */
public final class JdbcWorkoutSessionRepository implements WorkoutSessionRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcWorkoutSessionRepository(DataSource dataSource, ObjectMapper json) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public Optional<WorkoutSession> findByIdAndUser(UUID sessionId, UUID userId) {
        return query("WHERE ws.id = ? AND ws.user_id = ?", bytes(sessionId), bytes(userId))
                .stream().findFirst();
    }

    @Override
    public Optional<WorkoutSession> findByUserAndClientKey(UUID userId, String clientSessionKey) {
        return query("WHERE ws.user_id = ? AND ws.client_session_key = ?", bytes(userId), clientSessionKey)
                .stream().findFirst();
    }

    @Override
    public StartState findStartStateForUpdate(UUID userId, String clientSessionKey) {
        Objects.requireNonNull(userId, "user id must not be null");
        Objects.requireNonNull(clientSessionKey, "client session key must not be null");
        if (jdbc.queryForList(
                "SELECT HEX(id) FROM user_account WHERE id = ? FOR UPDATE",
                String.class, bytes(userId)).isEmpty()) {
            throw new WorkoutSessionService.SessionNotFoundException();
        }
        Optional<WorkoutSession> exact = query(
                "WHERE ws.user_id = ? AND ws.client_session_key = ? FOR UPDATE",
                bytes(userId), clientSessionKey).stream().findFirst();
        Optional<WorkoutSession> active = query("""
                WHERE ws.user_id = ?
                  AND ws.status IN ('CREATED', 'IN_PROGRESS', 'PAUSED', 'COMPLETING')
                ORDER BY ws.started_at, ws.id
                LIMIT 1 FOR UPDATE
                """, bytes(userId)).stream().findFirst();
        return new StartState(exact, active);
    }

    @Override
    public WorkoutSession create(WorkoutSession session) {
        Objects.requireNonNull(session, "session must not be null");
        return Objects.requireNonNull(transactions.execute(ignored -> {
            List<WorkoutSession> existing = query(
                    "WHERE ws.user_id = ? AND ws.client_session_key = ? FOR UPDATE",
                    bytes(session.userId()), session.clientSessionKey());
            if (!existing.isEmpty()) {
                WorkoutSession persisted = existing.getFirst();
                if (!persisted.hasSameSource(
                        session.planId(), session.planVersionNumber(), session.trainingDayCode())) {
                    throw new WorkoutSessionService.IdempotencyConflictException();
                }
                return persisted;
            }
            jdbc.update("""
                    INSERT INTO workout_session
                        (id, user_id, plan_id, plan_version_id, training_day_id,
                         client_session_key, status, started_at, completed_at, sync_version)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, bytes(session.id()), bytes(session.userId()), bytes(session.planId()),
                    bytes(session.planVersionId()), bytes(session.trainingDayId()),
                    session.clientSessionKey(), session.status().name(), Timestamp.from(session.startedAt()),
                    session.completedAt().map(Timestamp::from).orElse(null), session.version());
            session.exercises().forEach(exercise -> insertSnapshot(session, exercise));
            return findByIdAndUser(session.id(), session.userId()).orElseThrow();
        }));
    }

    @Override
    public WorkoutSession update(WorkoutSession session, long expectedVersion) {
        Objects.requireNonNull(session, "session must not be null");
        return Objects.requireNonNull(transactions.execute(ignored -> {
            int updated = jdbc.update("""
                    UPDATE workout_session
                    SET status = ?, completed_at = ?, sync_version = ?
                    WHERE id = ? AND user_id = ? AND sync_version = ?
                    """, session.status().name(), session.completedAt().map(Timestamp::from).orElse(null),
                    session.version(), bytes(session.id()), bytes(session.userId()), expectedVersion);
            if (updated == 1) {
                return findByIdAndUser(session.id(), session.userId()).orElseThrow();
            }
            WorkoutSession current = findByIdAndUser(session.id(), session.userId())
                    .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
            throw new WorkoutSessionService.VersionConflictException(current.version());
        }));
    }

    @Override
    public WorkoutSession complete(WorkoutSession terminalSession, long expectedVersion) {
        Objects.requireNonNull(terminalSession, "terminal session must not be null");
        if (!terminalSession.status().terminal() || terminalSession.version() != expectedVersion + 2) {
            throw new IllegalArgumentException("atomic completion must contain both validated transitions");
        }
        return Objects.requireNonNull(transactions.execute(ignored -> {
            int updated = jdbc.update("""
                    UPDATE workout_session
                    SET status = ?, completed_at = ?, sync_version = ?
                    WHERE id = ? AND user_id = ? AND sync_version = ?
                      AND status IN ('IN_PROGRESS', 'PAUSED')
                    """, terminalSession.status().name(),
                    terminalSession.completedAt().map(Timestamp::from).orElseThrow(), terminalSession.version(),
                    bytes(terminalSession.id()), bytes(terminalSession.userId()), expectedVersion);
            if (updated == 1) {
                return findByIdAndUser(terminalSession.id(), terminalSession.userId()).orElseThrow();
            }
            WorkoutSession current = findByIdAndUser(terminalSession.id(), terminalSession.userId())
                    .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
            if (current.version() != expectedVersion) {
                throw new WorkoutSessionService.VersionConflictException(current.version());
            }
            throw new IllegalStateException("workout session cannot be completed from " + current.status());
        }));
    }

    @Override
    public WorkoutSession replaceExercise(
            UUID userId, UUID sessionId, UUID snapshotId, long expectedVersion,
            WorkoutExerciseSnapshot replacement) {
        Objects.requireNonNull(replacement, "replacement must not be null");
        return Objects.requireNonNull(transactions.execute(ignored -> {
            int sessionUpdated = jdbc.update("""
                    UPDATE workout_session
                    SET sync_version = sync_version + 1
                    WHERE id = ? AND user_id = ? AND sync_version = ?
                      AND status IN ('IN_PROGRESS', 'PAUSED')
                    """, bytes(sessionId), bytes(userId), expectedVersion);
            if (sessionUpdated != 1) {
                WorkoutSession current = findByIdAndUser(sessionId, userId)
                        .orElseThrow(WorkoutSessionService.SessionNotFoundException::new);
                if (current.version() != expectedVersion) {
                    throw new WorkoutSessionService.VersionConflictException(current.version());
                }
                throw new IllegalStateException("workout session does not accept exercise replacement");
            }
            Map<String, Object> overlay = new LinkedHashMap<>();
            overlay.put("exerciseCode", replacement.exerciseCode());
            overlay.put("exerciseName", replacement.exerciseName());
            overlay.put("contentVersion", replacement.contentVersion());
            overlay.put("equipment", replacement.equipment().stream().sorted().toList());
            overlay.put("prescription", prescriptionFacts(replacement.prescription()));
            int snapshotUpdated = jdbc.update("""
                    UPDATE workout_exercise_snapshot
                    SET replacement_snapshot_json = ?, replacement_revision = replacement_revision + 1,
                        status = 'REPLACED'
                    WHERE id = ? AND session_id = ?
                    """, writeJson(overlay), bytes(snapshotId), bytes(sessionId));
            if (snapshotUpdated != 1) {
                throw new WorkoutSessionService.SessionNotFoundException();
            }
            return findByIdAndUser(sessionId, userId).orElseThrow();
        }));
    }

    @Override
    public List<WorkoutSession> findHistory(
            UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit) {
        if (limit < 1) throw new IllegalArgumentException("history limit must be positive");
        if (beforeStartedAt.isPresent() != beforeId.isPresent()) {
            throw new IllegalArgumentException("history cursor fields must be provided together");
        }
        if (beforeStartedAt.isPresent()) {
            return query("""
                    WHERE ws.user_id = ? AND ws.status IN ('COMPLETED', 'ABORTED')
                      AND (ws.started_at < ? OR (ws.started_at = ? AND ws.id < ?))
                    ORDER BY ws.started_at DESC, ws.id DESC LIMIT ?
                    """, bytes(userId), Timestamp.from(beforeStartedAt.orElseThrow()),
                    Timestamp.from(beforeStartedAt.orElseThrow()), bytes(beforeId.orElseThrow()), limit);
        }
        return query("""
                WHERE ws.user_id = ? AND ws.status IN ('COMPLETED', 'ABORTED')
                ORDER BY ws.started_at DESC, ws.id DESC LIMIT ?
                """, bytes(userId), limit);
    }

    private void insertSnapshot(WorkoutSession session, WorkoutExerciseSnapshot exercise) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("trainingDayCode", session.trainingDayCode());
        facts.put("exerciseCode", exercise.exerciseCode());
        facts.put("exerciseName", exercise.exerciseName());
        facts.put("contentVersion", exercise.contentVersion());
        facts.put("equipment", exercise.equipment().stream().sorted().toList());
        int firstOrder = session.exercises().stream()
                .mapToInt(WorkoutExerciseSnapshot::order)
                .min()
                .orElseThrow();
        if (exercise.order() == firstOrder) {
            session.warmupPrescription().ifPresent(
                    warmup -> facts.put("warmupPrescription", warmupFacts(warmup)));
        }
        Map<String, Object> prescription = prescriptionFacts(exercise.prescription());
        jdbc.update("""
                INSERT INTO workout_exercise_snapshot
                    (id, session_id, source_plan_exercise_id, source_training_day_id,
                     source_plan_version_id, exercise_order, exercise_snapshot_json,
                     prescription_snapshot_json, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(exercise.id()), bytes(session.id()), bytes(exercise.sourcePlanExerciseId()),
                bytes(session.trainingDayId()), bytes(session.planVersionId()), exercise.order(),
                writeJson(facts), writeJson(prescription), exercise.status().name());
    }

    private List<WorkoutSession> query(String clause, Object... arguments) {
        return jdbc.query("""
                SELECT ws.id, ws.user_id, ws.plan_id, ws.plan_version_id, pv.version_no,
                       ws.training_day_id, ws.client_session_key, ws.status, ws.started_at,
                       ws.completed_at, ws.sync_version
                FROM workout_session ws
                JOIN training_plan_version pv
                  ON pv.id = ws.plan_version_id AND pv.plan_id = ws.plan_id
                """ + clause, (row, ignored) -> readSession(row), arguments);
    }

    private WorkoutSession readSession(ResultSet row) throws SQLException {
        UUID sessionId = uuid(row.getBytes("id"));
        List<WorkoutExerciseSnapshot> exercises = jdbc.query("""
                SELECT id, source_plan_exercise_id, exercise_order,
                       exercise_snapshot_json, replacement_snapshot_json,
                       prescription_snapshot_json, status
                FROM workout_exercise_snapshot
                WHERE session_id = ? ORDER BY exercise_order
                """, (snapshotRow, ignored) -> readSnapshot(sessionId, snapshotRow), bytes(sessionId));
        if (exercises.isEmpty()) {
            throw new IllegalStateException("persisted workout session has no snapshots");
        }
        Map<String, Object> facts = readJson(jdbc.queryForObject("""
                SELECT exercise_snapshot_json FROM workout_exercise_snapshot
                WHERE session_id = ? ORDER BY exercise_order LIMIT 1
                """, String.class, bytes(sessionId)));
        Timestamp completedAt = row.getTimestamp("completed_at");
        return new WorkoutSession(
                sessionId, uuid(row.getBytes("user_id")), uuid(row.getBytes("plan_id")),
                uuid(row.getBytes("plan_version_id")), row.getInt("version_no"),
                uuid(row.getBytes("training_day_id")), text(facts, "trainingDayCode"),
                row.getString("client_session_key"), WorkoutStatus.valueOf(row.getString("status")),
                row.getTimestamp("started_at").toInstant(),
                Optional.ofNullable(completedAt).map(Timestamp::toInstant),
                row.getLong("sync_version"), exercises, readWarmup(facts));
    }

    private WorkoutExerciseSnapshot readSnapshot(UUID sessionId, ResultSet row) throws SQLException {
        Map<String, Object> facts = readJson(row.getString("exercise_snapshot_json"));
        String replacementJson = row.getString("replacement_snapshot_json");
        Map<String, Object> effective = replacementJson == null ? facts : readJson(replacementJson);
        Map<String, Object> originalPrescriptionFacts = readJson(row.getString("prescription_snapshot_json"));
        Set<String> originalEquipment = equipment(facts);
        Set<String> equipment = equipment(effective);
        WorkoutExerciseSnapshot.Prescription originalPrescription = prescription(originalPrescriptionFacts);
        WorkoutExerciseSnapshot.Prescription effectivePrescription = replacementJson == null
                ? originalPrescription
                : effective.containsKey("prescription")
                        ? prescription(objectMap(effective.get("prescription"), "prescription"))
                        : originalPrescription.forReplacement(originalEquipment, equipment);
        return new WorkoutExerciseSnapshot(
                uuid(row.getBytes("id")), sessionId, uuid(row.getBytes("source_plan_exercise_id")),
                row.getInt("exercise_order"), text(effective, "exerciseCode"), text(effective, "exerciseName"),
                text(effective, "contentVersion"), equipment,
                effectivePrescription,
                WorkoutExerciseSnapshot.Status.valueOf(row.getString("status")));
    }

    private static Map<String, Object> prescriptionFacts(
            WorkoutExerciseSnapshot.Prescription prescription) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("workSets", prescription.workSets());
        facts.put("repMin", prescription.repMin());
        facts.put("repMax", prescription.repMax());
        facts.put("restSeconds", prescription.restSeconds());
        facts.put("weightStatus", prescription.weightStatus());
        prescription.targetWeightKg().ifPresent(weight -> facts.put("targetWeightKg", weight));
        facts.put("unit", prescription.unit());
        return facts;
    }

    private static WorkoutExerciseSnapshot.Prescription prescription(Map<String, Object> facts) {
        return new WorkoutExerciseSnapshot.Prescription(
                number(facts, "workSets"), number(facts, "repMin"),
                number(facts, "repMax"), number(facts, "restSeconds"),
                text(facts, "weightStatus"), decimal(facts, "targetWeightKg"),
                text(facts, "unit"));
    }

    private static Set<String> equipment(Map<String, Object> facts) {
        Object value = facts.get("equipment");
        return value instanceof List<?> values
                ? values.stream().map(String::valueOf)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("workout snapshot cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted workout snapshot is invalid", exception);
        }
    }

    private static Map<String, Object> warmupFacts(WorkoutWarmupPrescription value) {
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("schemaVersion", value.schemaVersion());
        facts.put("ruleVersion", value.ruleVersion());
        facts.put("generalWarmup", Map.of(
                "occurrences", value.generalWarmup().occurrences(),
                "durationSeconds", value.generalWarmup().durationSeconds()));
        value.rampWarmup().ifPresent(ramp -> {
            Map<String, Object> rampFacts = new LinkedHashMap<>();
            rampFacts.put("exerciseId", ramp.exerciseId().toString());
            rampFacts.put("exerciseOrder", ramp.exerciseOrder());
            rampFacts.put("status", ramp.status().name());
            ramp.equipmentType().ifPresent(type -> rampFacts.put("equipmentType", type));
            rampFacts.put("sets", ramp.sets().stream()
                    .map(set -> Map.of("weightKg", set.weightKg(), "reps", set.reps()))
                    .toList());
            ramp.calibrationCode().ifPresent(code -> rampFacts.put("calibrationCode", code));
            ramp.calibrationMessage().ifPresent(message -> rampFacts.put("calibrationMessage", message));
            facts.put("rampWarmup", rampFacts);
        });
        facts.put("countsTowardTrainingVolume", value.countsTowardTrainingVolume());
        facts.put("countsTowardProgression", value.countsTowardProgression());
        return facts;
    }

    private static Optional<WorkoutWarmupPrescription> readWarmup(Map<String, Object> facts) {
        Object raw = facts.get("warmupPrescription");
        if (raw == null) {
            return Optional.empty();
        }
        Map<String, Object> warmup = objectMap(raw, "warmupPrescription");
        Map<String, Object> general = objectMap(warmup.get("generalWarmup"), "generalWarmup");
        Optional<WorkoutWarmupPrescription.RampWarmup> ramp = Optional.ofNullable(warmup.get("rampWarmup"))
                .map(value -> objectMap(value, "rampWarmup"))
                .map(value -> {
                    Object rawSets = value.get("sets");
                    if (!(rawSets instanceof List<?> sets)) {
                        throw new IllegalStateException("persisted ramp warmup sets are invalid");
                    }
                    List<WorkoutWarmupPrescription.RampSet> rampSets = sets.stream()
                            .map(item -> objectMap(item, "rampWarmup.set"))
                            .map(item -> new WorkoutWarmupPrescription.RampSet(
                                    decimalRequired(item, "weightKg"), number(item, "reps")))
                            .toList();
                    return new WorkoutWarmupPrescription.RampWarmup(
                            UUID.fromString(text(value, "exerciseId")),
                            number(value, "exerciseOrder"),
                            WorkoutWarmupPrescription.RampStatus.valueOf(text(value, "status")),
                            optionalText(value, "equipmentType"),
                            rampSets,
                            optionalText(value, "calibrationCode"),
                            optionalText(value, "calibrationMessage"));
                });
        return Optional.of(new WorkoutWarmupPrescription(
                text(warmup, "schemaVersion"),
                text(warmup, "ruleVersion"),
                new WorkoutWarmupPrescription.GeneralWarmup(
                        number(general, "occurrences"), number(general, "durationSeconds")),
                ramp,
                booleanValue(warmup, "countsTowardTrainingVolume"),
                booleanValue(warmup, "countsTowardProgression")));
    }

    private static Map<String, Object> objectMap(Object value, String field) {
        if (!(value instanceof Map<?, ?> values)) {
            throw new IllegalStateException("persisted workout snapshot object is missing: " + field);
        }
        Map<String, Object> mapped = new LinkedHashMap<>();
        values.forEach((key, item) -> mapped.put(String.valueOf(key), item));
        return mapped;
    }

    private static Optional<String> optionalText(Map<String, Object> values, String field) {
        return Optional.ofNullable(values.get(field)).map(String::valueOf);
    }

    private static java.math.BigDecimal decimalRequired(Map<String, Object> values, String field) {
        return decimal(values, field)
                .orElseThrow(() -> new IllegalStateException("persisted workout snapshot decimal is missing: " + field));
    }

    private static boolean booleanValue(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Boolean flag)) {
            throw new IllegalStateException("persisted workout snapshot boolean is missing: " + field);
        }
        return flag;
    }

    private static String text(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("persisted workout snapshot field is missing: " + field);
        }
        return String.valueOf(value);
    }

    private static int number(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("persisted workout snapshot number is missing: " + field);
        }
        return number.intValue();
    }

    private static Optional<java.math.BigDecimal> decimal(Map<String, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? Optional.empty()
                : Optional.of(new java.math.BigDecimal(String.valueOf(value)));
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
