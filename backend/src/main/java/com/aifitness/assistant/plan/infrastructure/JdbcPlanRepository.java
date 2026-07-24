package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.plan.application.PlanRepository;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.FieldLock;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;

/** MySQL adapter that builds, seals and activates every immutable plan version in one transaction. */
public final class JdbcPlanRepository implements PlanRepository {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Function<String, UUID> exerciseIds;

    public JdbcPlanRepository(
            DataSource dataSource, ObjectMapper json, Function<String, UUID> exerciseIds) {
        this.jdbc = new JdbcTemplate(dataSource);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        this.json = java.util.Objects.requireNonNull(json, "json must not be null");
        this.exerciseIds = java.util.Objects.requireNonNull(exerciseIds, "exerciseIds must not be null");
    }

    @Override
    public Optional<TrainingPlan> findActiveByUser(UUID userId) {
        List<UUID> ids = jdbc.query(
                "SELECT id FROM training_plan WHERE user_id = ? AND status = 'ACTIVE'",
                (row, ignored) -> uuid(row.getBytes(1)), bytes(userId));
        return ids.stream().findFirst().flatMap(id -> findByIdAndUser(id, userId));
    }

    @Override
    public Optional<TrainingPlan> findByIdAndUser(UUID planId, UUID userId) {
        List<PlanHeader> plans = jdbc.query("""
                SELECT p.id, p.user_id, pv.version_no
                FROM training_plan p
                JOIN training_plan_version pv ON pv.id = p.active_version_id
                WHERE p.id = ? AND p.user_id = ?
                """, (row, ignored) -> new PlanHeader(
                        uuid(row.getBytes(1)), uuid(row.getBytes(2)), row.getInt(3)),
                bytes(planId), bytes(userId));
        if (plans.isEmpty()) {
            return Optional.empty();
        }
        PlanHeader header = plans.getFirst();
        List<TrainingPlanVersion> versions = jdbc.query("""
                SELECT id, version_no, source_type, split_type, template_version,
                       rule_version, change_summary_json, created_at
                FROM training_plan_version
                WHERE plan_id = ? AND sealed_at IS NOT NULL
                ORDER BY version_no
                """, (row, ignored) -> readVersion(header.id(), row), bytes(planId));
        return Optional.of(new TrainingPlan(header.id(), header.userId(), versions, header.activeVersion()));
    }

    @Override
    public TrainingPlan create(UUID userId, TrainingPlanVersion firstVersion) {
        return transactions.execute(status -> {
                List<byte[]> owners = jdbc.query(
                        "SELECT id FROM user_account WHERE id = ? FOR UPDATE",
                        (row, ignored) -> row.getBytes(1), bytes(userId));
                if (owners.isEmpty()) {
                    throw new PlanVersionService.PlanNotFoundException();
                }
                Integer activePlans = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM training_plan WHERE user_id = ? AND status = 'ACTIVE'",
                        Integer.class, bytes(userId));
                if (activePlans != null && activePlans > 0) {
                    throw new PlanVersionService.ActivePlanAlreadyExistsException();
                }
                jdbc.update("""
                        INSERT INTO training_plan (id, user_id, status, created_at)
                        VALUES (?, ?, 'ACTIVE', ?)
                        """, bytes(firstVersion.planId()), bytes(userId), Timestamp.from(firstVersion.createdAt()));
                insertAndSeal(firstVersion);
                int activated = jdbc.update("""
                        UPDATE training_plan SET active_version_id = ?
                        WHERE id = ? AND user_id = ? AND active_version_id IS NULL
                        """, bytes(firstVersion.id()), bytes(firstVersion.planId()), bytes(userId));
                if (activated != 1) {
                    throw new PlanVersionService.VersionConflictException(0);
                }
            return new TrainingPlan(firstVersion.planId(), userId, List.of(firstVersion), 1);
        });
    }

    @Override
    public TrainingPlan append(
            UUID userId, UUID planId, int expectedVersion, TrainingPlanVersion version) {
        return transactions.execute(status -> {
                List<ActiveVersion> active = jdbc.query("""
                        SELECT p.active_version_id, pv.version_no
                        FROM training_plan p
                        JOIN training_plan_version pv ON pv.id = p.active_version_id
                        WHERE p.id = ? AND p.user_id = ? AND p.status = 'ACTIVE'
                        FOR UPDATE
                        """, (row, ignored) -> new ActiveVersion(
                                uuid(row.getBytes(1)), row.getInt(2)), bytes(planId), bytes(userId));
                if (active.isEmpty()) {
                    throw new PlanVersionService.PlanNotFoundException();
                }
                ActiveVersion current = active.getFirst();
                if (current.versionNumber() != expectedVersion) {
                    throw new PlanVersionService.VersionConflictException(current.versionNumber());
                }
                insertAndSeal(version);
                int activated = jdbc.update("""
                        UPDATE training_plan SET active_version_id = ?
                        WHERE id = ? AND user_id = ? AND active_version_id = ?
                        """, bytes(version.id()), bytes(planId), bytes(userId), bytes(current.id()));
                if (activated != 1) {
                    throw new PlanVersionService.VersionConflictException(current.versionNumber());
                }
                TrainingPlan persisted = findByIdAndUser(planId, userId)
                        .orElseThrow(PlanVersionService.PlanNotFoundException::new);
                if (persisted.activeVersionNumber() != version.versionNumber()) {
                    throw new IllegalStateException("active version was not persisted");
                }
            return persisted;
        });
    }

    private void insertAndSeal(TrainingPlanVersion version) {
        PlanDraft plan = version.plan();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("name", plan.name());
        summary.put("contentVersion", version.ruleReference().contentVersion());
        summary.put("confirmedWarnings", version.confirmedWarningCodes());
        jdbc.update("""
                INSERT INTO training_plan_version
                    (id, plan_id, version_no, source_type, split_type, frequency,
                     template_version, rule_version, change_summary_json, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(version.id()), bytes(version.planId()), version.versionNumber(),
                version.sourceType().name(), plan.templateCode(), Math.max(2, plan.days().size()),
                version.ruleReference().templateVersion(), version.ruleReference().ruleVersion(),
                writeJson(summary), Timestamp.from(version.createdAt()));

        int dayOrder = 0;
        for (PlanDraft.Day day : plan.days()) {
            dayOrder++;
            UUID dayId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO training_day
                        (id, plan_version_id, day_order, name, estimated_minutes)
                    VALUES (?, ?, ?, ?, 0)
                    """, bytes(dayId), bytes(version.id()), dayOrder, day.name());
            int exerciseOrder = 0;
            for (PlanDraft.Exercise exercise : day.exercises()) {
                exerciseOrder++;
                UUID planExerciseId = UUID.randomUUID();
                Map<String, Object> prescription = new LinkedHashMap<>();
                prescription.put("dayCode", day.code());
                prescription.put("exerciseCode", exercise.exerciseCode());
                prescription.put("workSets", exercise.workSets());
                prescription.put("repMin", exercise.repMin());
                prescription.put("repMax", exercise.repMax());
                prescription.put("restSeconds", exercise.restSeconds());
                exercise.targetWeightKg().ifPresent(weight -> prescription.put("targetWeightKg", weight));
                jdbc.update("""
                        INSERT INTO plan_exercise
                            (id, training_day_id, plan_version_id, exercise_id, exercise_order,
                             prescription_json, weight_status, status)
                        VALUES (?, ?, ?, ?, ?, ?, ?, 'ACTIVE')
                        """, bytes(planExerciseId), bytes(dayId), bytes(version.id()),
                        bytes(exerciseIds.apply(exercise.exerciseCode())), exerciseOrder,
                        writeJson(prescription), exercise.weightStatus().name());
                String prefix = "/days/" + day.code() + "/exercises/" + exercise.exerciseCode() + "/";
                plan.locks().forEach((path, lockStatus) -> {
                    if (path.startsWith(prefix)) {
                        jdbc.update("""
                                INSERT INTO plan_field_lock
                                    (plan_exercise_id, field_path, lock_status, locked_at)
                                VALUES (?, ?, ?, ?)
                                """, bytes(planExerciseId), path, lockStatus.name(),
                                Timestamp.from(version.createdAt()));
                    }
                });
            }
        }
        int sealed = jdbc.update(
                "UPDATE training_plan_version SET sealed_at = ? WHERE id = ? AND sealed_at IS NULL",
                Timestamp.from(version.createdAt()), bytes(version.id()));
        if (sealed != 1) {
            throw new IllegalStateException("plan version could not be sealed");
        }
    }

    private TrainingPlanVersion readVersion(UUID planId, ResultSet row) throws SQLException {
        UUID versionId = uuid(row.getBytes("id"));
        Map<String, Object> summary = readJson(row.getString("change_summary_json"));
        List<PlanDraft.Day> days = jdbc.query("""
                SELECT id, name FROM training_day
                WHERE plan_version_id = ? ORDER BY day_order
                """, (dayRow, ignored) -> readDay(versionId, dayRow), bytes(versionId));
        Map<String, FieldLock.Status> locks = new LinkedHashMap<>();
        jdbc.query("""
                SELECT pfl.field_path, pfl.lock_status
                FROM plan_field_lock pfl
                JOIN plan_exercise pe ON pe.id = pfl.plan_exercise_id
                WHERE pe.plan_version_id = ?
                """, (org.springframework.jdbc.core.RowCallbackHandler) result -> locks.put(
                        result.getString(1), FieldLock.Status.valueOf(result.getString(2))), bytes(versionId));
        PlanDraft plan = new PlanDraft(
                row.getString("split_type"), String.valueOf(summary.get("name")), days, locks);
        Object warnings = summary.get("confirmedWarnings");
        Set<String> confirmed = warnings instanceof List<?> values
                ? values.stream().map(String::valueOf).collect(java.util.stream.Collectors.toUnmodifiableSet())
                : Set.of();
        return new TrainingPlanVersion(
                versionId, planId, row.getInt("version_no"),
                TrainingPlanVersion.SourceType.valueOf(row.getString("source_type")), plan,
                new RuleReference(
                        row.getString("rule_version"), row.getString("template_version"),
                        String.valueOf(summary.get("contentVersion"))),
                confirmed, row.getTimestamp("created_at").toInstant());
    }

    private PlanDraft.Day readDay(UUID versionId, ResultSet dayRow) throws SQLException {
        UUID dayId = uuid(dayRow.getBytes("id"));
        List<StoredExercise> stored = jdbc.query("""
                SELECT prescription_json, weight_status FROM plan_exercise
                WHERE training_day_id = ? AND plan_version_id = ? ORDER BY exercise_order
                """, (row, ignored) -> {
                    Map<String, Object> values = readJson(row.getString("prescription_json"));
                    return new StoredExercise(values, row.getString("weight_status"));
                }, bytes(dayId), bytes(versionId));
        List<PlanDraft.Exercise> exercises = new ArrayList<>(stored.size());
        for (StoredExercise item : stored) {
            Map<String, Object> value = item.values();
            exercises.add(new PlanDraft.Exercise(
                    String.valueOf(value.get("exerciseCode")), number(value, "workSets"),
                    number(value, "repMin"), number(value, "repMax"), number(value, "restSeconds"),
                    PlanDraft.WeightStatus.valueOf(item.weightStatus()), decimal(value, "targetWeightKg")));
        }
        String dayCode = stored.isEmpty() ? null : String.valueOf(stored.getFirst().values().get("dayCode"));
        return new PlanDraft.Day(dayCode, dayRow.getString("name"), exercises);
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("plan data cannot be serialized", exception);
        }
    }

    private Map<String, Object> readJson(String value) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted plan data is invalid", exception);
        }
    }

    private static int number(Map<String, Object> value, String field) {
        return ((Number) value.get(field)).intValue();
    }

    private static Optional<java.math.BigDecimal> decimal(Map<String, Object> value, String field) {
        Object number = value.get(field);
        return number == null ? Optional.empty() : Optional.of(new java.math.BigDecimal(String.valueOf(number)));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record PlanHeader(UUID id, UUID userId, int activeVersion) {}
    private record ActiveVersion(UUID id, int versionNumber) {}
    private record StoredExercise(Map<String, Object> values, String weightStatus) {}
}
