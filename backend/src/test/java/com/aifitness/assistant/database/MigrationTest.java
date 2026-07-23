package com.aifitness.assistant.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

class MigrationTest {

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4");

    private static Flyway flyway;

    @Test
    void cleanMysql84DatabaseMigratesExactlyOnceAndValidates() {
        migrateEmptyMysqlDatabase();
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .extracting(Object::toString)
                .containsExactly("001", "002", "003", "004", "005");
    }

    private static synchronized void migrateEmptyMysqlDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required only for the MySQL 8.4 Testcontainers acceptance tests");
        if (flyway != null) {
            return;
        }
        MYSQL.start();
        flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .load();

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void shipsTheFiveAppendOnlyMigrationStepsRequiredForTheEmptyMysqlDatabase() {
        List<String> migrationFiles = List.of(
                "db/migration/V001__identity_profile.sql",
                "db/migration/V002__content_rules.sql",
                "db/migration/V003__plan_versions.sql",
                "db/migration/V004__workout_sync.sql",
                "db/migration/V005__progression_ai_audit.sql");

        assertThat(migrationFiles)
                .allSatisfy(resource -> assertThat(getClass().getClassLoader().getResource(resource))
                        .as("migration resource %s", resource)
                        .isNotNull());
    }

    @Test
    void migrationScriptsDeclareTheRequiredMysqlBoundaryConstraints() throws IOException {
        assertThat(readMigration("V001__identity_profile.sql"))
                .contains("CREATE TABLE user_account", "CREATE TABLE user_identity", "UNIQUE (provider, subject_cipher)");
        assertThat(readMigration("V003__plan_versions.sql"))
                .contains(
                        "UNIQUE (plan_id, version_no)",
                        "FOREIGN KEY (active_version_id, id) REFERENCES training_plan_version (id, plan_id)",
                        "trg_training_plan_version_immutable_update");
        assertThat(readMigration("V004__workout_sync.sql"))
                .contains(
                        "UNIQUE (user_id, client_session_key)",
                        "UNIQUE (session_exercise_id, client_set_key)",
                        "FOREIGN KEY (plan_id, user_id) REFERENCES training_plan (id, user_id)");
        assertThat(readMigration("V005__progression_ai_audit.sql"))
                .contains("UNIQUE (source_session_id, exercise_id, algorithm_version)");
    }

    @Test
    void databaseEnforcesRequiredUniquenessAndCrossUserSessionIsolation() throws Exception {
        migrateEmptyMysqlDatabase();
        String userId = newId();
        String secondUserId = newId();
        String exerciseId = newId();
        String planId = newId();
        String versionId = newId();
        String secondPlanId = newId();
        String secondVersionId = newId();
        String dayId = newId();
        String planExerciseId = newId();
        String sessionId = newId();
        String sessionExerciseId = newId();

        execute("INSERT INTO user_account (id, status, created_at) VALUES (%s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(userId)));
        execute("INSERT INTO user_account (id, status, created_at) VALUES (%s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(secondUserId)));
        execute("INSERT INTO user_identity (id, user_id, provider, subject_cipher, status, created_at) VALUES (%s, %s, 'WECHAT_MINI_PROGRAM', 'cipher', 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId)));
        assertThatThrownBy(() -> execute("INSERT INTO user_identity (id, user_id, provider, subject_cipher, status, created_at) VALUES (%s, %s, 'WECHAT_MINI_PROGRAM', 'cipher', 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId))))
                .isInstanceOf(SQLException.class);

        execute("INSERT INTO exercise (id, measurement_type, movement_pattern, difficulty, status, content_version, review_status) VALUES (%s, 'WEIGHTED', 'SQUAT', 'BEGINNER', 'ACTIVE', 'v1', 'AI_VALIDATED')".formatted(binary(exerciseId)));
        execute("INSERT INTO training_plan (id, user_id, status, created_at) VALUES (%s, %s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(planId), binary(userId)));
        execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at) VALUES (%s, %s, 1, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6))".formatted(binary(versionId), binary(planId)));
        assertThatThrownBy(() -> execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at) VALUES (%s, %s, 1, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(planId))))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("UPDATE training_plan_version SET split_type = 'UPPER_LOWER' WHERE id = %s".formatted(binary(versionId))))
                .isInstanceOf(SQLException.class);
        execute("INSERT INTO training_plan (id, user_id, status, created_at) VALUES (%s, %s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(secondPlanId), binary(userId)));
        execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at) VALUES (%s, %s, 1, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6))".formatted(binary(secondVersionId), binary(secondPlanId)));
        assertThatThrownBy(() -> execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(secondVersionId), binary(planId))))
                .isInstanceOf(SQLException.class);

        execute("INSERT INTO training_day (id, plan_version_id, day_order, name, estimated_minutes) VALUES (%s, %s, 1, 'Day 1', 45)".formatted(binary(dayId), binary(versionId)));
        execute("INSERT INTO plan_exercise (id, training_day_id, exercise_id, exercise_order, prescription_json, weight_status, status) VALUES (%s, %s, %s, 1, JSON_OBJECT(), 'KNOWN', 'ACTIVE')".formatted(binary(planExerciseId), binary(dayId), binary(exerciseId)));
        execute("INSERT INTO workout_session (id, user_id, plan_id, plan_version_id, training_day_id, client_session_key, status, started_at, sync_version) VALUES (%s, %s, %s, %s, %s, 'session-key', 'IN_PROGRESS', UTC_TIMESTAMP(6), 0)".formatted(binary(sessionId), binary(userId), binary(planId), binary(versionId), binary(dayId)));
        assertThatThrownBy(() -> execute("INSERT INTO workout_session (id, user_id, plan_id, plan_version_id, training_day_id, client_session_key, status, started_at, sync_version) VALUES (%s, %s, %s, %s, %s, 'session-key', 'IN_PROGRESS', UTC_TIMESTAMP(6), 0)".formatted(binary(newId()), binary(userId), binary(planId), binary(versionId), binary(dayId))))
                .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO workout_session (id, user_id, plan_id, plan_version_id, training_day_id, client_session_key, status, started_at, sync_version) VALUES (%s, %s, %s, %s, %s, 'other-user-key', 'IN_PROGRESS', UTC_TIMESTAMP(6), 0)".formatted(binary(newId()), binary(secondUserId), binary(planId), binary(versionId), binary(dayId))))
                .isInstanceOf(SQLException.class);

        execute("INSERT INTO workout_exercise_snapshot (id, session_id, source_plan_exercise_id, exercise_order, exercise_snapshot_json, prescription_snapshot_json, status) VALUES (%s, %s, %s, 1, JSON_OBJECT(), JSON_OBJECT(), 'ACTIVE')".formatted(binary(sessionExerciseId), binary(sessionId), binary(planExerciseId)));
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, set_type, set_order, target_json, unit, completion_status, server_revision) VALUES (%s, %s, 'set-key', 'WORKING', 1, JSON_OBJECT(), 'KG', 'PENDING', 0)".formatted(binary(newId()), binary(sessionExerciseId)));
        assertThatThrownBy(() -> execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, set_type, set_order, target_json, unit, completion_status, server_revision) VALUES (%s, %s, 'set-key', 'WORKING', 2, JSON_OBJECT(), 'KG', 'PENDING', 0)".formatted(binary(newId()), binary(sessionExerciseId))))
                .isInstanceOf(SQLException.class);

        execute("INSERT INTO progression_recommendation (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json, reason_code, input_snapshot_json, algorithm_version, user_decision, created_at) VALUES (%s, %s, %s, %s, 'INCREASE', JSON_OBJECT(), JSON_OBJECT(), 'TARGET_REPS_MET', JSON_OBJECT(), 'v1', 'PENDING', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId), binary(exerciseId), binary(sessionId)));
        assertThatThrownBy(() -> execute("INSERT INTO progression_recommendation (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json, reason_code, input_snapshot_json, algorithm_version, user_decision, created_at) VALUES (%s, %s, %s, %s, 'INCREASE', JSON_OBJECT(), JSON_OBJECT(), 'TARGET_REPS_MET', JSON_OBJECT(), 'v1', 'PENDING', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId), binary(exerciseId), binary(sessionId))))
                .isInstanceOf(SQLException.class);
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    private static String binary(String uuid) {
        return "UUID_TO_BIN('" + uuid + "')";
    }

    private static String readMigration(String filename) throws IOException {
        try (InputStream input = Objects.requireNonNull(
                MigrationTest.class.getResourceAsStream("/db/migration/" + filename),
                () -> "missing migration resource " + filename)) {
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @AfterAll
    static void stopMysqlContainer() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
    }
}
