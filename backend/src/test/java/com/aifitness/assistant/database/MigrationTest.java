package com.aifitness.assistant.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
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
    void shipsTheFiveAppendOnlyMigrationStepsRequiredForTheEmptyMysqlDatabase() {
        assertThat(List.of(
                "db/migration/V001__identity_profile.sql",
                "db/migration/V002__content_rules.sql",
                "db/migration/V003__plan_versions.sql",
                "db/migration/V004__workout_sync.sql",
                "db/migration/V005__progression_ai_audit.sql"))
                .allSatisfy(resource -> assertThat(getClass().getClassLoader().getResource(resource))
                        .as("migration resource %s", resource)
                        .isNotNull());
    }

    @Test
    void migrationScriptsDeclareSealingOwnershipAndFactImmutabilityBoundaries() throws IOException {
        assertThat(readMigration("V003__plan_versions.sql")).contains(
                "sealed_at DATETIME(6) NULL",
                "trg_training_plan_version_reject_presealed_insert",
                "trg_training_plan_version_seal_once",
                "trg_training_plan_active_version_must_be_sealed",
                "trg_training_day_reject_write_when_sealed",
                "trg_plan_exercise_reject_write_when_sealed",
                "trg_plan_field_lock_reject_write_when_sealed");
        assertThat(readMigration("V004__workout_sync.sql")).contains(
                "source_training_day_id BINARY(16) NOT NULL",
                "source_plan_version_id BINARY(16) NOT NULL",
                "FOREIGN KEY (session_id, source_training_day_id, source_plan_version_id)",
                "FOREIGN KEY (source_plan_exercise_id, source_training_day_id, source_plan_version_id)",
                "trg_workout_session_plan_version_must_be_sealed",
                "trg_workout_session_source_immutable",
                "trg_workout_snapshot_fact_immutable",
                "trg_workout_set_fact_immutable",
                "trg_workout_session_history_immutable_delete",
                "trg_workout_snapshot_history_immutable_delete",
                "trg_workout_set_history_immutable_delete",
                "trg_workout_set_revision_immutable_update",
                "trg_workout_set_revision_immutable_delete");
        assertThat(readMigration("V005__progression_ai_audit.sql")).contains(
                "applied_plan_id BINARY(16) NULL",
                "FOREIGN KEY (applied_plan_id, user_id) REFERENCES training_plan (id, user_id)",
                "FOREIGN KEY (applied_plan_version_id, applied_plan_id) REFERENCES training_plan_version (id, plan_id)",
                "ck_progression_recommendation_applied_plan_pair",
                "trg_progression_recommendation_fact_immutable",
                "trg_progression_recommendation_applied_plan_must_be_sealed",
                "trg_progression_recommendation_history_immutable_delete");
    }

    @Test
    void cleanMysql84DatabaseMigratesExactlyOnceAndValidates() {
        migrateEmptyMysqlDatabase();
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .extracting(Object::toString)
                .containsExactly("001", "002", "003", "004", "005");
    }

    @Test
    void databaseEnforcesSealedPlanAndImmutableWorkoutAndProgressionFacts() throws Exception {
        migrateEmptyMysqlDatabase();
        databaseEnforcesRequiredUniqueConstraintsAndCrossUserSessions();
        databaseAllowsBuildingThenSealingAndActivatingOnlyTheSamePlanVersion();
        databaseRejectsUnsealedSealingEntryPoints();
        databaseRejectsCrossPlanSnapshotsAndProtectsWorkoutFacts();
        progressionFactsAreImmutableAndAppliedPlanMustBelongToTheUser();
        databaseRejectsHistoricalDeletesAndRevisionRewrites();
    }

    private static void databaseAllowsBuildingThenSealingAndActivatingOnlyTheSamePlanVersion() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        PlanFixture plan = createPlanFixture(userId, exerciseId);

        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(plan.versionId()), binary(plan.planId())));
        assertThat(queryOne("SELECT BIN_TO_UUID(active_version_id) FROM training_plan WHERE id = %s".formatted(binary(plan.planId()))))
                .isEqualTo(plan.versionId());

        String unsealedVersionId = newId();
        execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at) VALUES (%s, %s, 2, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6))".formatted(binary(unsealedVersionId), binary(plan.planId())));
        assertRejected("45000", "active training plan version must be sealed", () ->
                execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(unsealedVersionId), binary(plan.planId()))));
        assertRejected("45000", "training plan version is sealed", () ->
                execute("INSERT INTO training_day (id, plan_version_id, day_order, name, estimated_minutes) VALUES (%s, %s, 2, 'extra', 45)".formatted(binary(newId()), binary(plan.versionId()))));
        assertRejected("45000", "training plan version is sealed", () ->
                execute("UPDATE plan_exercise SET status = 'SKIPPED' WHERE id = %s".formatted(binary(plan.planExerciseId()))));
        assertRejected("45000", "training plan version is sealed", () ->
                execute("DELETE FROM plan_field_lock WHERE plan_exercise_id = %s AND field_path = 'restSeconds'".formatted(binary(plan.planExerciseId()))));
        assertRejected("45000", "training plan version may only transition from unsealed to sealed", () ->
                execute("UPDATE training_plan_version SET sealed_at = UTC_TIMESTAMP(6) WHERE id = %s".formatted(binary(plan.versionId()))));

        PlanFixture otherPlan = createPlanFixture(userId, exerciseId);
        seal(otherPlan);
        assertRejected(1452, "fk_training_plan_active_version", () ->
                execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(otherPlan.versionId()), binary(plan.planId()))));
    }

    private static void databaseEnforcesRequiredUniqueConstraintsAndCrossUserSessions() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        execute("INSERT INTO user_identity (id, user_id, provider, subject_cipher, status, created_at) VALUES (%s, %s, 'WECHAT_MINI_PROGRAM', 'cipher', 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId)));
        assertRejected(1062, "uq_user_identity_provider_subject", () ->
                execute("INSERT INTO user_identity (id, user_id, provider, subject_cipher, status, created_at) VALUES (%s, %s, 'WECHAT_MINI_PROGRAM', 'cipher', 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId))));

        PlanFixture plan = createPlanFixture(userId, exerciseId);
        assertRejected(1062, "uq_training_plan_version_number", () ->
                execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at) VALUES (%s, %s, 1, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(plan.planId()))));
        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(plan.versionId()), binary(plan.planId())));
        String sessionId = createSession(plan, userId);
        assertRejected(1062, "uq_workout_session_user_key", () ->
                execute("INSERT INTO workout_session (id, user_id, plan_id, plan_version_id, training_day_id, client_session_key, status, started_at, sync_version) VALUES (%s, %s, %s, %s, %s, '%s', 'IN_PROGRESS', UTC_TIMESTAMP(6), 0)".formatted(binary(newId()), binary(userId), binary(plan.planId()), binary(plan.versionId()), binary(plan.dayId()), sessionId)));
        String otherUserId = createUser();
        assertRejected(1452, "fk_workout_session_plan_user", () ->
                execute("INSERT INTO workout_session (id, user_id, plan_id, plan_version_id, training_day_id, client_session_key, status, started_at, sync_version) VALUES (%s, %s, %s, %s, %s, 'other-user-session', 'IN_PROGRESS', UTC_TIMESTAMP(6), 0)".formatted(binary(newId()), binary(otherUserId), binary(plan.planId()), binary(plan.versionId()), binary(plan.dayId()))));

        String snapshotId = newId();
        execute(snapshotInsert(snapshotId, sessionId, plan));
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, set_type, set_order, target_json, unit, completion_status, server_revision) VALUES (%s, %s, 'set-key', 'WORKING', 1, JSON_OBJECT(), 'KG', 'PENDING', 0)".formatted(binary(newId()), binary(snapshotId)));
        assertRejected(1062, "uq_workout_set_client_key", () ->
                execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, set_type, set_order, target_json, unit, completion_status, server_revision) VALUES (%s, %s, 'set-key', 'WORKING', 2, JSON_OBJECT(), 'KG', 'PENDING', 0)".formatted(binary(newId()), binary(snapshotId))));
        execute("INSERT INTO progression_recommendation (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json, reason_code, input_snapshot_json, algorithm_version, user_decision, created_at) VALUES (%s, %s, %s, %s, 'INCREASE', JSON_OBJECT(), JSON_OBJECT(), 'TARGET_REPS_MET', JSON_OBJECT(), 'v1', 'PENDING', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId), binary(exerciseId), binary(sessionId)));
        assertRejected(1062, "uq_progression_recommendation_source", () ->
                execute("INSERT INTO progression_recommendation (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json, reason_code, input_snapshot_json, algorithm_version, user_decision, created_at) VALUES (%s, %s, %s, %s, 'INCREASE', JSON_OBJECT(), JSON_OBJECT(), 'TARGET_REPS_MET', JSON_OBJECT(), 'v1', 'PENDING', UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId), binary(exerciseId), binary(sessionId))));
    }

    private static void databaseRejectsUnsealedSealingEntryPoints() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        String planId = newId();
        execute("INSERT INTO training_plan (id, user_id, status, created_at) VALUES (%s, %s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(planId), binary(userId)));
        assertRejected("45000", "training plan version must be created unsealed", () ->
                execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at, sealed_at) VALUES (%s, %s, 1, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(planId))));

        PlanFixture unsealedPlan = createPlanFixture(userId, exerciseId);
        assertRejected("45000", "workout session plan version must be sealed", () -> createSession(unsealedPlan, userId));

        PlanFixture sourcePlan = createPlanFixture(userId, exerciseId);
        seal(sourcePlan);
        String sourceSessionId = createSession(sourcePlan, userId);
        String recommendationId = createRecommendation(userId, exerciseId, sourceSessionId);
        assertRejected("45000", "applied progression plan version must be sealed", () ->
                execute("UPDATE progression_recommendation SET applied_plan_id = %s, applied_plan_version_id = %s WHERE id = %s".formatted(binary(unsealedPlan.planId()), binary(unsealedPlan.versionId()), binary(recommendationId))));
    }

    private static void databaseRejectsCrossPlanSnapshotsAndProtectsWorkoutFacts() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        PlanFixture plan = createPlanFixture(userId, exerciseId);
        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(plan.versionId()), binary(plan.planId())));
        String sessionId = createSession(plan, userId);

        String snapshotId = newId();
        execute(snapshotInsert(snapshotId, sessionId, plan));
        assertRejected("45000", "workout session source fields are immutable", () ->
                execute("UPDATE workout_session SET started_at = UTC_TIMESTAMP(6) WHERE id = %s".formatted(binary(sessionId))));
        execute("UPDATE workout_session SET status = 'PAUSED' WHERE id = %s".formatted(binary(sessionId)));
        assertThat(queryOne("SELECT status FROM workout_session WHERE id = %s".formatted(binary(sessionId)))).isEqualTo("PAUSED");
        assertRejected("45000", "workout exercise snapshot facts are immutable", () ->
                execute("UPDATE workout_exercise_snapshot SET exercise_snapshot_json = JSON_OBJECT('changed', true) WHERE id = %s".formatted(binary(snapshotId))));

        String setId = newId();
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, set_type, set_order, target_json, unit, completion_status, server_revision) VALUES (%s, %s, 'set-key', 'WORKING', 1, JSON_OBJECT('reps', 8), 'KG', 'PENDING', 0)".formatted(binary(setId), binary(snapshotId)));
        assertRejected("45000", "workout set facts are immutable", () ->
                execute("UPDATE workout_set SET target_json = JSON_OBJECT('reps', 10) WHERE id = %s".formatted(binary(setId))));
        execute("UPDATE workout_set SET actual_reps = 8, completion_status = 'COMPLETED', server_revision = 1 WHERE id = %s".formatted(binary(setId)));

        PlanFixture otherPlan = createPlanFixture(userId, exerciseId);
        seal(otherPlan);
        assertRejected(1452, "fk_workout_snapshot_plan_source", () ->
                execute(snapshotInsert(newId(), sessionId, otherPlan.planExerciseId(), plan.dayId(), plan.versionId())));

        String otherUserId = createUser();
        PlanFixture otherUserPlan = createPlanFixture(otherUserId, exerciseId);
        seal(otherUserPlan);
        assertRejected(1452, "fk_workout_snapshot_plan_source", () ->
                execute(snapshotInsert(newId(), sessionId, otherUserPlan.planExerciseId(), plan.dayId(), plan.versionId())));
    }

    private static void progressionFactsAreImmutableAndAppliedPlanMustBelongToTheUser() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        PlanFixture plan = createPlanFixture(userId, exerciseId);
        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s".formatted(binary(plan.versionId()), binary(plan.planId())));
        String sessionId = createSession(plan, userId);
        String recommendationId = newId();
        execute(recommendationInsert(recommendationId, userId, exerciseId, sessionId));

        execute("UPDATE progression_recommendation SET user_decision = 'APPLIED', applied_plan_id = %s, applied_plan_version_id = %s WHERE id = %s".formatted(binary(plan.planId()), binary(plan.versionId()), binary(recommendationId)));
        assertThat(queryOne("SELECT user_decision FROM progression_recommendation WHERE id = %s".formatted(binary(recommendationId)))).isEqualTo("APPLIED");
        assertRejected("45000", "progression recommendation facts are immutable", () ->
                execute("UPDATE progression_recommendation SET current_json = JSON_OBJECT('weight', 30) WHERE id = %s".formatted(binary(recommendationId))));

        String otherUserId = createUser();
        PlanFixture otherUserPlan = createPlanFixture(otherUserId, exerciseId);
        seal(otherUserPlan);
        assertRejected(1452, "fk_progression_recommendation_applied_plan_user", () ->
                execute("UPDATE progression_recommendation SET applied_plan_id = %s, applied_plan_version_id = %s WHERE id = %s".formatted(binary(otherUserPlan.planId()), binary(otherUserPlan.versionId()), binary(recommendationId))));
        assertRejected(3819, "ck_progression_recommendation_applied_plan_pair", () ->
                execute("UPDATE progression_recommendation SET applied_plan_id = NULL, applied_plan_version_id = %s WHERE id = %s".formatted(binary(plan.versionId()), binary(recommendationId))));
    }

    private static void databaseRejectsHistoricalDeletesAndRevisionRewrites() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();

        PlanFixture sessionPlan = sealedPlan(userId, exerciseId);
        String sessionId = createSession(sessionPlan, userId);
        assertRejected("45000", "workout session history is immutable", () ->
                execute("DELETE FROM workout_session WHERE id = %s".formatted(binary(sessionId))));

        PlanFixture snapshotPlan = sealedPlan(userId, exerciseId);
        String snapshotSessionId = createSession(snapshotPlan, userId);
        String snapshotId = newId();
        execute(snapshotInsert(snapshotId, snapshotSessionId, snapshotPlan));
        assertRejected("45000", "workout exercise snapshot history is immutable", () ->
                execute("DELETE FROM workout_exercise_snapshot WHERE id = %s".formatted(binary(snapshotId))));

        PlanFixture setPlan = sealedPlan(userId, exerciseId);
        String setSessionId = createSession(setPlan, userId);
        String setSnapshotId = newId();
        execute(snapshotInsert(setSnapshotId, setSessionId, setPlan));
        String setId = newId();
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, set_type, set_order, target_json, unit, completion_status, server_revision) VALUES (%s, %s, 'delete-set', 'WORKING', 1, JSON_OBJECT(), 'KG', 'PENDING', 0)".formatted(binary(setId), binary(setSnapshotId)));
        assertRejected("45000", "workout set history is immutable", () ->
                execute("DELETE FROM workout_set WHERE id = %s".formatted(binary(setId))));

        PlanFixture recommendationPlan = sealedPlan(userId, exerciseId);
        String recommendationSessionId = createSession(recommendationPlan, userId);
        String recommendationId = createRecommendation(userId, exerciseId, recommendationSessionId);
        assertRejected("45000", "progression recommendation history is immutable", () ->
                execute("DELETE FROM progression_recommendation WHERE id = %s".formatted(binary(recommendationId))));

        String revisionId = newId();
        execute("INSERT INTO workout_set_revision (id, workout_set_id, revision_no, before_json, after_json, reason, created_at) VALUES (%s, %s, 1, JSON_OBJECT(), JSON_OBJECT(), 'correction', UTC_TIMESTAMP(6))".formatted(binary(revisionId), binary(setId)));
        assertRejected("45000", "workout set revisions are immutable", () ->
                execute("UPDATE workout_set_revision SET reason = 'late sync' WHERE id = %s".formatted(binary(revisionId))));
        assertRejected("45000", "workout set revisions are immutable", () ->
                execute("DELETE FROM workout_set_revision WHERE id = %s".formatted(binary(revisionId))));
    }

    private static synchronized void migrateEmptyMysqlDatabase() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
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

    private static String createUser() throws Exception {
        String userId = newId();
        execute("INSERT INTO user_account (id, status, created_at) VALUES (%s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(userId)));
        return userId;
    }

    private static String createExercise() throws Exception {
        String exerciseId = newId();
        execute("INSERT INTO exercise (id, measurement_type, movement_pattern, difficulty, status, content_version, review_status) VALUES (%s, 'WEIGHTED', 'SQUAT', 'BEGINNER', 'ACTIVE', 'v1', 'AI_VALIDATED')".formatted(binary(exerciseId)));
        return exerciseId;
    }

    private static PlanFixture createPlanFixture(String userId, String exerciseId) throws Exception {
        PlanFixture fixture = new PlanFixture(newId(), newId(), newId(), newId());
        execute("INSERT INTO training_plan (id, user_id, status, created_at) VALUES (%s, %s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(fixture.planId()), binary(userId)));
        execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, created_at) VALUES (%s, %s, 1, 'INITIAL', 'FULL_BODY', 3, 'template-v1', 'rule-v1', UTC_TIMESTAMP(6))".formatted(binary(fixture.versionId()), binary(fixture.planId())));
        execute("INSERT INTO training_day (id, plan_version_id, day_order, name, estimated_minutes) VALUES (%s, %s, 1, 'Day 1', 45)".formatted(binary(fixture.dayId()), binary(fixture.versionId())));
        execute("INSERT INTO plan_exercise (id, training_day_id, plan_version_id, exercise_id, exercise_order, prescription_json, weight_status, status) VALUES (%s, %s, %s, %s, 1, JSON_OBJECT(), 'KNOWN', 'ACTIVE')".formatted(binary(fixture.planExerciseId()), binary(fixture.dayId()), binary(fixture.versionId()), binary(exerciseId)));
        execute("INSERT INTO plan_field_lock (plan_exercise_id, field_path, lock_status, locked_at) VALUES (%s, 'restSeconds', 'USER_LOCKED', UTC_TIMESTAMP(6))".formatted(binary(fixture.planExerciseId())));
        return fixture;
    }

    private static void seal(PlanFixture plan) throws Exception {
        execute("UPDATE training_plan_version SET sealed_at = UTC_TIMESTAMP(6) WHERE id = %s".formatted(binary(plan.versionId())));
    }

    private static PlanFixture sealedPlan(String userId, String exerciseId) throws Exception {
        PlanFixture plan = createPlanFixture(userId, exerciseId);
        seal(plan);
        return plan;
    }

    private static String createSession(PlanFixture plan, String userId) throws Exception {
        String sessionId = newId();
        execute("INSERT INTO workout_session (id, user_id, plan_id, plan_version_id, training_day_id, client_session_key, status, started_at, sync_version) VALUES (%s, %s, %s, %s, %s, '%s', 'IN_PROGRESS', UTC_TIMESTAMP(6), 0)".formatted(binary(sessionId), binary(userId), binary(plan.planId()), binary(plan.versionId()), binary(plan.dayId()), sessionId));
        return sessionId;
    }

    private static String snapshotInsert(String snapshotId, String sessionId, PlanFixture plan) {
        return snapshotInsert(snapshotId, sessionId, plan.planExerciseId(), plan.dayId(), plan.versionId());
    }

    private static String snapshotInsert(
            String snapshotId, String sessionId, String sourcePlanExerciseId, String sourceDayId, String sourceVersionId) {
        return "INSERT INTO workout_exercise_snapshot (id, session_id, source_plan_exercise_id, source_training_day_id, source_plan_version_id, exercise_order, exercise_snapshot_json, prescription_snapshot_json, status) VALUES (%s, %s, %s, %s, %s, 1, JSON_OBJECT(), JSON_OBJECT(), 'ACTIVE')"
                .formatted(binary(snapshotId), binary(sessionId), binary(sourcePlanExerciseId), binary(sourceDayId), binary(sourceVersionId));
    }

    private static String createRecommendation(String userId, String exerciseId, String sessionId) throws Exception {
        String recommendationId = newId();
        execute(recommendationInsert(recommendationId, userId, exerciseId, sessionId));
        return recommendationId;
    }

    private static String recommendationInsert(String recommendationId, String userId, String exerciseId, String sessionId) {
        return "INSERT INTO progression_recommendation (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json, reason_code, input_snapshot_json, algorithm_version, user_decision, created_at) VALUES (%s, %s, %s, %s, 'INCREASE', JSON_OBJECT(), JSON_OBJECT(), 'TARGET_REPS_MET', JSON_OBJECT(), 'v1', 'PENDING', UTC_TIMESTAMP(6))"
                .formatted(binary(recommendationId), binary(userId), binary(exerciseId), binary(sessionId));
    }

    private static void assertRejected(String sqlState, String messageFragment, ThrowingCallable action) {
        assertThatExceptionOfType(SQLException.class).isThrownBy(action).satisfies(error -> {
            assertThat(error.getSQLState()).isEqualTo(sqlState);
            if (messageFragment != null) {
                assertThat(error.getMessage()).contains(messageFragment);
            }
        });
    }

    private static void assertRejected(int errorCode, String messageFragment, ThrowingCallable action) {
        assertThatExceptionOfType(SQLException.class).isThrownBy(action).satisfies(error -> {
            assertThat(error.getErrorCode()).isEqualTo(errorCode);
            assertThat(error.getMessage()).contains(messageFragment);
        });
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryOne(String sql) throws Exception {
        try (Connection connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             ResultSet results = statement.executeQuery(sql)) {
            assertThat(results.next()).isTrue();
            return results.getString(1);
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

    private record PlanFixture(String planId, String versionId, String dayId, String planExerciseId) {
    }
}
