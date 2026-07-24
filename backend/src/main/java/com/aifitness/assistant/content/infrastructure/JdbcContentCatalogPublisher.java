package com.aifitness.assistant.content.infrastructure;

import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Publishes validated classpath catalogs into the relational reference tables used by plans. */
public final class JdbcContentCatalogPublisher {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;

    public JdbcContentCatalogPublisher(DataSource dataSource, ObjectMapper json) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.json = Objects.requireNonNull(json, "json must not be null");
    }

    public void publish(ExerciseCatalog exercises, PlanTemplateCatalog templates) {
        Objects.requireNonNull(exercises, "exercises must not be null");
        Objects.requireNonNull(templates, "templates must not be null");
        transactions.executeWithoutResult(status -> {
            publishExercises(exercises);
            publishAlternatives(exercises);
            publishTemplates(templates);
        });
    }

    private void publishExercises(ExerciseCatalog catalog) {
        for (ExerciseCatalog.Exercise exercise : catalog.exercises()) {
            byte[] exerciseId = bytes(exercise.stableId());
            jdbc.update("""
                    INSERT INTO exercise
                        (id, measurement_type, movement_pattern, difficulty, status,
                         content_version, review_status)
                    VALUES (?, ?, ?, 'BEGINNER', ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        movement_pattern = VALUES(movement_pattern),
                        status = VALUES(status),
                        content_version = VALUES(content_version),
                        review_status = VALUES(review_status)
                    """, exerciseId, measurementType(exercise), exercise.movementPattern(),
                    exercise.active() ? "ACTIVE" : "RETIRED",
                    catalog.metadata().version(), catalog.metadata().status().name());
            jdbc.update("DELETE FROM exercise_i18n WHERE exercise_id = ?", exerciseId);
            jdbc.update("""
                    INSERT INTO exercise_i18n
                        (exercise_id, locale, name, instructions_json, safety_tips_json)
                    VALUES (?, 'zh-CN', ?, ?, ?)
                    """, exerciseId, exercise.name(), writeJson(exercise.instructions()),
                    writeJson(exercise.safetyCues()));
            jdbc.update("DELETE FROM exercise_equipment WHERE exercise_id = ?", exerciseId);
            for (String equipment : exercise.equipment()) {
                jdbc.update("""
                        INSERT INTO exercise_equipment (exercise_id, equipment_type)
                        VALUES (?, ?)
                        """, exerciseId, equipment);
            }
            jdbc.update("DELETE FROM exercise_muscle WHERE exercise_id = ?", exerciseId);
            for (String muscle : exercise.primaryMuscles()) {
                jdbc.update("""
                        INSERT INTO exercise_muscle (exercise_id, muscle_code, role)
                        VALUES (?, ?, 'PRIMARY')
                        """, exerciseId, muscle);
            }
        }
    }

    private void publishAlternatives(ExerciseCatalog catalog) {
        for (ExerciseCatalog.Exercise exercise : catalog.exercises()) {
            byte[] exerciseId = bytes(exercise.stableId());
            jdbc.update("DELETE FROM exercise_alternative WHERE exercise_id = ?", exerciseId);
            for (ExerciseCatalog.Alternative alternative : exercise.alternatives()) {
                UUID alternativeId = UUID.nameUUIDFromBytes(
                        ("ai-fitness-exercise:" + alternative.exerciseCode())
                                .getBytes(StandardCharsets.UTF_8));
                jdbc.update("""
                        INSERT INTO exercise_alternative
                            (exercise_id, alternative_id, rank_no, review_status)
                        VALUES (?, ?, ?, ?)
                        """, exerciseId, bytes(alternativeId), alternative.rank(),
                        alternative.reviewStatus().name());
            }
        }
    }

    private void publishTemplates(PlanTemplateCatalog catalog) {
        for (PlanTemplateCatalog.Template template : catalog.templates()) {
            UUID id = UUID.nameUUIDFromBytes(("ai-fitness-template:"
                    + template.code() + ":" + catalog.metadata().version())
                    .getBytes(StandardCharsets.UTF_8));
            jdbc.update("""
                    INSERT INTO plan_template_version
                        (id, template_code, version, frequency, payload_json, status)
                    VALUES (?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        frequency = VALUES(frequency),
                        payload_json = VALUES(payload_json),
                        status = VALUES(status)
                    """, bytes(id), template.code(), catalog.metadata().version(),
                    template.sessionsPerWeek(), writeJson(template), catalog.metadata().status().name());
        }
    }

    private String writeJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("validated content cannot be serialized", exception);
        }
    }

    private static String measurementType(ExerciseCatalog.Exercise exercise) {
        return exercise.equipment().equals(java.util.Set.of("BODYWEIGHT"))
                ? "BODYWEIGHT"
                : "WEIGHTED";
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
