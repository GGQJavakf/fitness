package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** MySQL plan-module query that resolves real sealed plan source identifiers. */
public final class JdbcPlanWorkoutSnapshotQuery implements PlanWorkoutSnapshotQuery {
    private static final TypeReference<Map<String, Object>> JSON_MAP = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public JdbcPlanWorkoutSnapshotQuery(DataSource dataSource, ObjectMapper json) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    @Override
    public PlanDaySource load(UUID userId, UUID planId, int versionNumber, String trainingDayCode) {
        List<Header> headers = jdbc.query("""
                SELECT pv.id, td.id
                FROM training_plan p
                JOIN training_plan_version pv ON pv.id = p.active_version_id AND pv.plan_id = p.id
                JOIN training_day td ON td.plan_version_id = pv.id
                WHERE p.id = ? AND p.user_id = ? AND p.status = 'ACTIVE'
                  AND pv.version_no = ? AND pv.sealed_at IS NOT NULL
                  AND EXISTS (
                    SELECT 1 FROM plan_exercise pe
                    WHERE pe.training_day_id = td.id AND pe.plan_version_id = pv.id
                      AND JSON_UNQUOTE(JSON_EXTRACT(pe.prescription_json, '$.dayCode')) = ?)
                """, (row, ignored) -> new Header(uuid(row.getBytes(1)), uuid(row.getBytes(2))),
                bytes(planId), bytes(userId), versionNumber, trainingDayCode);
        if (headers.size() != 1) {
            throw new PlanSnapshotNotFoundException();
        }
        Header header = headers.getFirst();
        List<ExerciseSource> sources = jdbc.query("""
                SELECT pe.id AS plan_exercise_id, pe.exercise_order, pe.prescription_json, pe.weight_status,
                       e.id AS exercise_id, e.content_version, i18n.name
                FROM plan_exercise pe
                JOIN exercise e ON e.id = pe.exercise_id
                JOIN exercise_i18n i18n ON i18n.exercise_id = e.id AND i18n.locale = 'zh-CN'
                WHERE pe.training_day_id = ? AND pe.plan_version_id = ? AND pe.status = 'ACTIVE'
                ORDER BY pe.exercise_order
                """, (row, ignored) -> {
                    Map<String, Object> prescription = readJson(row.getString("prescription_json"));
                    UUID exerciseId = uuid(row.getBytes("exercise_id"));
                    Set<String> equipment = Set.copyOf(jdbc.queryForList(
                            "SELECT equipment_type FROM exercise_equipment WHERE exercise_id = ? ORDER BY equipment_type",
                            String.class, bytes(exerciseId)));
                    return new ExerciseSource(
                            uuid(row.getBytes("plan_exercise_id")), row.getInt("exercise_order"),
                            text(prescription, "exerciseCode"), row.getString("name"),
                            row.getString("content_version"), equipment,
                            number(prescription, "workSets"), number(prescription, "repMin"),
                            number(prescription, "repMax"), number(prescription, "restSeconds"),
                            row.getString("weight_status"), decimal(prescription, "targetWeightKg"), "KG");
                }, bytes(header.dayId()), bytes(header.versionId()));
        if (sources.isEmpty()) {
            throw new PlanSnapshotNotFoundException();
        }
        return new PlanDaySource(
                planId, header.versionId(), versionNumber, header.dayId(), trainingDayCode, sources);
    }

    private Map<String, Object> readJson(String value) {
        try {
            return json.readValue(value, JSON_MAP);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("persisted plan prescription is invalid", exception);
        }
    }

    private static String text(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new IllegalStateException("persisted plan prescription field is missing: " + field);
        }
        return String.valueOf(value);
    }

    private static int number(Map<String, Object> values, String field) {
        Object value = values.get(field);
        if (!(value instanceof Number number)) {
            throw new IllegalStateException("persisted plan prescription number is missing: " + field);
        }
        return number.intValue();
    }

    private static java.util.Optional<BigDecimal> decimal(Map<String, Object> values, String field) {
        Object value = values.get(field);
        return value == null ? java.util.Optional.empty()
                : java.util.Optional.of(new BigDecimal(String.valueOf(value)));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    private record Header(UUID versionId, UUID dayId) {}
}
