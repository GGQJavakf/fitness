package com.aifitness.assistant.database;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import com.mysql.cj.jdbc.MysqlDataSource;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import com.aifitness.assistant.common.domain.RuleReference;
import com.aifitness.assistant.testsupport.ExternalMysqlTls;
import com.aifitness.assistant.testsupport.ExternalMysqlDatabaseTarget;
import com.aifitness.assistant.testsupport.ExternalTestSecret;
import com.aifitness.assistant.testsupport.ExternalMysqlValidationMarker;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.domain.UserIdentity;
import com.aifitness.assistant.identity.infrastructure.JdbcIdentityRepository;
import com.aifitness.assistant.identity.infrastructure.JdbcSessionStore;
import com.aifitness.assistant.content.infrastructure.ClasspathContentCatalogRepository;
import com.aifitness.assistant.content.infrastructure.JdbcContentCatalogPublisher;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import com.aifitness.assistant.plan.infrastructure.JdbcPlanRepository;
import com.aifitness.assistant.plan.infrastructure.JdbcPlanWorkoutSnapshotQuery;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.profile.domain.PreferenceProfile;
import com.aifitness.assistant.profile.domain.UserProfile;
import com.aifitness.assistant.profile.infrastructure.JdbcProfileRepository;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionEngine;
import com.aifitness.assistant.progression.domain.EquipmentRoundingPolicy;
import com.aifitness.assistant.progression.domain.ProgressionInput;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import com.aifitness.assistant.progression.application.EffectiveSetSelector;
import com.aifitness.assistant.progression.infrastructure.JdbcExerciseTrendQuery;
import com.aifitness.assistant.progression.infrastructure.JdbcHistoricalProgressionFactProvider;
import com.aifitness.assistant.progression.infrastructure.JdbcRecommendationRepository;
import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyAudit;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyDataReader;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyExportRepository;
import com.aifitness.assistant.privacy.infrastructure.JdbcPrivacyRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.domain.WorkoutWarmupPrescription;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.SyncConflict;
import com.aifitness.assistant.workout.infrastructure.JdbcSyncConflictRepository;
import com.aifitness.assistant.workout.infrastructure.JdbcWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.JdbcWorkoutHistoryRepository;
import com.aifitness.assistant.workout.infrastructure.JdbcWorkoutSetRepository;
import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

class MigrationTest {

    private static final String JDBC_URL_ENVIRONMENT = "FITNESS_TEST_MYSQL_JDBC_URL";
    private static final String USERNAME_ENVIRONMENT = "FITNESS_TEST_MYSQL_USERNAME";
    private static final String PASSWORD_ENVIRONMENT = "FITNESS_TEST_MYSQL_PASSWORD";
    private static final String PASSWORD_FILE_ENVIRONMENT = "FITNESS_TEST_MYSQL_PASSWORD_FILE";
    private static final String ALLOW_REMOTE_PROPERTY = "fitness.test.mysql.allow-remote";
    private static final String ALLOW_UNVERIFIED_TLS_PROPERTY =
            "fitness.test.mysql.allow-unverified-tls";
    private static final String ALLOW_PINNED_CA_PROPERTY = "fitness.test.mysql.allow-pinned-ca";
    private static final String TRUST_STORE_ENVIRONMENT = "FITNESS_TEST_MYSQL_TRUST_STORE";
    private static final String TRUST_STORE_TYPE_ENVIRONMENT = "FITNESS_TEST_MYSQL_TRUST_STORE_TYPE";
    private static final String TRUST_STORE_PASSWORD_ENVIRONMENT = "FITNESS_TEST_MYSQL_TRUST_STORE_PASSWORD";
    private static final String TRUST_STORE_PASSWORD_FILE_ENVIRONMENT =
            "FITNESS_TEST_MYSQL_TRUST_STORE_PASSWORD_FILE";
    private static final String EXTERNAL_DATABASE_NAME_ERROR =
            "External test database must use an approved disposable name";
    private static final String VERIFICATION_RUN_ID_PROPERTY = "fitness.verification.run-id";
    private static final String VALIDATION_MARKER_PROPERTY = "fitness.verification.marker";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.44");

    private static DataSource dataSource;
    private static Flyway flyway;
    private static LegacyWorkoutSetUpgradeFixture legacyWorkoutSetUpgradeFixture;
    private static String externalValidationJdbcUrl;
    private static Level previousFlywayLogLevel;
    private static boolean flywayLogLevelOverridden;

    @BeforeAll
    static void clearPreviousExternalValidationMarker() {
        String markerPath = System.getProperty(VALIDATION_MARKER_PROPERTY);
        if (markerPath != null && !markerPath.isBlank()) {
            ExternalMysqlValidationMarker.clear(markerPath);
        }
    }

    @Test
    void externalMysqlConfigurationRequiresJdbcUrlAndUsername() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> externalDataSource("", "root", ""))
                .withMessage("External MySQL JDBC URL must be configured");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> externalDataSource(
                        "jdbc:mysql://127.0.0.1:33306/fitness_m0", " ", "secret-marker"))
                .withMessage("External MySQL username must be configured")
                .withMessageNotContaining("secret-marker");
    }

    @Test
    void externalMysqlConfigurationRejectsNonMysqlJdbcUrls() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> externalDataSource(
                        "jdbc:postgresql://127.0.0.1:33306/fitness_m0", "root", "secret-marker"))
                .withMessage("External test database must use a MySQL JDBC URL")
                .withMessageNotContaining("secret-marker");
    }

    @Test
    void externalMysqlConfigurationRejectsNonLoopbackHosts() {
        List.of("database.internal", "127.attacker.example", "127.0.0.1.evil", "127.0.0.999")
                .forEach(host -> {
                    Throwable failure = catchThrowable(() -> externalDataSource(
                            "jdbc:mysql://" + host + ":33306/fitness_m0",
                            "root",
                            "secret-marker"));
                    assertThat(failure)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessageNotContaining("secret-marker");
                });
    }

    @Test
    void externalMysqlConfigurationRejectsMultiHostAndMissingPortForms() {
        for (String jdbcUrl : List.of(
                "jdbc:mysql://approved.internal:3306,other.internal:3307/fitness_m0",
                "jdbc:mysql://approved.internal/fitness_m0",
                "jdbc:mysql://address=(host=approved.internal)(port=3306)/fitness_m0")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> externalDataSource(
                            jdbcUrl, "root", "secret-marker", true))
                    .withMessage("External MySQL JDBC URL must target exactly one host and port")
                    .withMessageNotContaining("secret-marker");
        }
    }

    @Test
    void externalMysqlConfigurationAllowsRemoteOnlyWithExplicitOptIn() {
        assertThat(((MysqlDataSource) externalDataSource(
                        "jdbc:mysql://192.0.2.10:33306/fitness_m0",
                        "root",
                        "secret-marker",
                        true)).getUrl())
                .isEqualTo("jdbc:mysql://192.0.2.10:33306/fitness_m0?sslMode=VERIFY_IDENTITY");
    }

    @Test
    void externalMysqlConfigurationAllowsOnlyStrictlyNamedDisposableSchemas() {
        assertThat(((MysqlDataSource) externalDataSource(
                        "jdbc:mysql://192.0.2.10:33306/fitness_verify_20260815a1b2",
                        "root",
                        "secret-marker",
                        true)).getUrl())
                .isEqualTo(
                        "jdbc:mysql://192.0.2.10:33306/fitness_verify_20260815a1b2"
                                + "?sslMode=VERIFY_IDENTITY");

        for (String database : List.of(
                "fitness_verify_short",
                "fitness_verify_20260815-A1B2",
                "fitness_verify_20260815a1b2_extra_suffix_that_is_too_long",
                "production")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> externalDataSource(
                            "jdbc:mysql://192.0.2.10:33306/" + database,
                            "root",
                            "secret-marker",
                            true))
                    .withMessage("External test database must use an approved disposable name")
                    .withMessageNotContaining("secret-marker");
        }
    }

    @Test
    void externalMysqlConfigurationUsesPinnedCaOnlyWithExplicitCompleteConfiguration()
            throws IOException, SQLException {
        var trustStore = java.nio.file.Files.createTempFile("fitness-mysql-validation-", ".p12");
        try {
            MysqlDataSource dataSource = (MysqlDataSource) externalDataSource(
                    "jdbc:mysql://192.0.2.10:33306/fitness_m0",
                    "root",
                    "secret-marker",
                    true,
                    true,
                    trustStore.toString(),
                    "PKCS12",
                    "trust-store-secret-marker");

            assertThat(dataSource.getUrl())
                    .isEqualTo("jdbc:mysql://192.0.2.10:33306/fitness_m0?sslMode=VERIFY_CA");
            assertThat(dataSource.getFallbackToSystemTrustStore()).isFalse();
            assertThat(dataSource.getTrustCertificateKeyStorePassword())
                    .isEqualTo("trust-store-secret-marker");
        } finally {
            java.nio.file.Files.deleteIfExists(trustStore);
        }
    }

    @Test
    void externalMysqlConfigurationUsesEncryptedOnlyModeOnlyWithExplicitOptIn() {
        MysqlDataSource dataSource = (MysqlDataSource) externalDataSource(
                "jdbc:mysql://192.0.2.10:33306/fitness_m0",
                "root",
                "secret-marker",
                true,
                true,
                false,
                null,
                null,
                null);

        assertThat(dataSource.getUrl())
                .isEqualTo("jdbc:mysql://192.0.2.10:33306/fitness_m0?sslMode=REQUIRED");
    }

    @Test
    void externalMysqlConfigurationRejectsConflictingTlsOptIns() throws IOException {
        var trustStore = java.nio.file.Files.createTempFile("fitness-mysql-validation-", ".p12");
        try {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> externalDataSource(
                            "jdbc:mysql://192.0.2.10:33306/fitness_m0",
                            "root",
                            "secret-marker",
                            true,
                            true,
                            true,
                            trustStore.toString(),
                            "PKCS12",
                            "trust-store-secret-marker"))
                    .withMessage("External MySQL TLS modes are mutually exclusive")
                    .withMessageNotContaining("secret-marker")
                    .withMessageNotContaining("trust-store-secret-marker");
        } finally {
            java.nio.file.Files.deleteIfExists(trustStore);
        }
    }

    @Test
    void externalMysqlConfigurationRejectsOtherDatabaseNames() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> externalDataSource(
                        "jdbc:mysql://127.0.0.1:33306/mysql", "root", "secret-marker"))
                .withMessage(EXTERNAL_DATABASE_NAME_ERROR)
                .withMessageNotContaining("secret-marker");
    }

    @Test
    void externalMysqlConfigurationRejectsUrlUserInfoQueryAndFragmentWithoutLeakingThem() {
        List.of(
                        "jdbc:mysql://secret-marker@127.0.0.1:33306/fitness_m0",
                        "jdbc:mysql://127.0.0.1:33306/fitness_m0?secret-marker=true",
                        "jdbc:mysql://127.0.0.1:33306/fitness_m0#secret-marker")
                .forEach(jdbcUrl -> {
                    Throwable failure = catchThrowable(() -> externalDataSource(
                            jdbcUrl, "root", "secret-marker"));

                    assertThat(failure)
                            .isInstanceOf(IllegalArgumentException.class)
                            .hasMessage("External MySQL JDBC URL must not include user-info, query, or fragment");
                    assertThrowableChainDoesNotContain(failure, "secret-marker");
                });
    }

    @Test
    void malformedExternalMysqlJdbcUrlDoesNotExposeOriginalInput() {
        Throwable failure = catchThrowable(() -> externalDataSource(
                "jdbc:mysql://127.0.0.1:33306/fitness_m0 secret-marker", "root", "secret-marker"));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("External MySQL JDBC URL is invalid")
                .hasNoCause();
        assertThrowableChainDoesNotContain(failure, "secret-marker");
    }

    @Test
    void externalMysqlConnectionFailuresDoNotExposeDriverDetails() throws SQLException {
        DataSource failingDataSource = new MysqlDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("secret-marker", "08001", 1045);
            }
        };

        Throwable failure = catchThrowable(() -> validateExternalDatabase(failingDataSource));

        assertThat(failure)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Unable to validate external test database")
                .hasNoCause();
        assertThrowableChainDoesNotContain(failure, "secret-marker");
    }

    @Test
    void externalMysqlServerMustBeMysql8() {
        validateExternalServerVersion("MySQL", 8, 0);
        validateExternalServerVersion("MySQL", 8, 4);
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateExternalServerVersion("MariaDB", 8, 0))
                .withMessage("External test database server must be MySQL 8");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> validateExternalServerVersion("MySQL", 9, 0))
                .withMessage("External test database server must be MySQL 8");
    }

    @Test
    void shipsTheAppendOnlyMigrationStepsRequiredForTheEmptyMysqlDatabase() {
        assertThat(List.of(
                "db/migration/V001__identity_profile.sql",
                "db/migration/V002__content_rules.sql",
                "db/migration/V003__plan_versions.sql",
                "db/migration/V004__workout_sync.sql",
                "db/migration/V005__progression_ai_audit.sql",
                "db/migration/V006__equipment_client_key.sql",
                "db/migration/V007__privacy_requests.sql",
                "db/migration/V008__privacy_retention_lifecycle.sql",
                "db/migration/V009__identity_sessions.sql",
                "db/migration/V010__profile_collection_versions.sql",
                "db/migration/V011__privacy_export_artifacts.sql",
                "db/migration/V012__workout_set_idempotency.sql",
                "db/migration/V013__sync_conflict_resolution.sql",
                "db/migration/V014__workout_exercise_replacement_overlay.sql",
                "db/migration/V015__progression_recommendation_lifecycle.sql",
                "db/migration/V016__progression_decision_idempotency.sql",
                "db/migration/V017__workout_completion_outbox.sql",
                "db/migration/V018__shared_privacy_operational_state.sql",
                "db/migration/V019__workout_set_logical_void.sql",
                "db/migration/V020__workout_set_conflict_corrections.sql",
                "db/migration/V021__shared_auth_and_plan_warning_state.sql",
                "db/migration/V022__workout_set_safety_flag.sql",
                "db/migration/V023__backfill_workout_set_idempotency.sql",
                "db/migration/V024__workout_recovery_confirmation.sql"))
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
                "trg_progression_recommendation_must_start_unapplied",
                "trg_progression_recommendation_applied_plan_must_be_sealed",
                "trg_progression_recommendation_history_immutable_delete");
        assertThat(readMigration("V006__equipment_client_key.sql")).contains(
                "client_equipment_key BINARY(16)",
                "UNIQUE KEY uq_user_equipment_user_client_key (user_id, client_equipment_key)");
        assertThat(readMigration("V007__privacy_requests.sql")).contains(
                "privacy_deletion_request",
                "uq_privacy_deletion_active_user",
                "privacy_required_retention",
                "trg_privacy_required_retention_immutable_update",
                "trg_privacy_required_retention_immutable_delete");
        assertThat(readMigration("V008__privacy_retention_lifecycle.sql")).contains(
                "MODIFY COLUMN retained_until DATETIME(6) NOT NULL",
                "DROP TRIGGER trg_privacy_required_retention_immutable_update",
                "trg_privacy_required_retention_require_expiry_insert",
                "trg_privacy_required_retention_controlled_update",
                "privacy_retention_lifecycle_audit",
                "PAYLOAD_PURGED");
        assertThat(readMigration("V009__identity_sessions.sql")).contains(
                "auth_session",
                "access_token_digest VARBINARY(32) NOT NULL",
                "refresh_token_digest VARBINARY(32) NOT NULL",
                "CONSTRAINT uq_auth_session_access_digest UNIQUE",
                "CONSTRAINT uq_auth_session_refresh_digest UNIQUE",
                "KEY idx_auth_session_user_status",
                "ck_auth_session_status");
        assertThat(readMigration("V010__profile_collection_versions.sql")).contains(
                "user_profile_collection_version",
                "equipment_version BIGINT UNSIGNED NOT NULL DEFAULT 0",
                "preference_version BIGINT UNSIGNED NOT NULL DEFAULT 0",
                "FOREIGN KEY (user_id) REFERENCES user_account (id)",
                "INSERT INTO user_profile_collection_version",
                "THEN 1 ELSE 0 END");
        assertThat(readMigration("V011__privacy_export_artifacts.sql")).contains(
                "privacy_export_artifact",
                "payload_json JSON NOT NULL",
                "idx_privacy_export_user_expires",
                "trg_privacy_export_artifact_immutable_update");
        assertThat(readMigration("V012__workout_set_idempotency.sql")).contains(
                "client_operation_seq BIGINT UNSIGNED",
                "payload_digest BINARY(32)",
                "applied_session_version BIGINT UNSIGNED",
                "trg_workout_set_fact_immutable");
        assertThat(readMigration("V013__sync_conflict_resolution.sql")).contains(
                "resolution VARCHAR(32)",
                "sync_version BIGINT UNSIGNED",
                "ck_sync_conflict_resolution_state");
        assertThat(readMigration("V014__workout_exercise_replacement_overlay.sql")).contains(
                "ALTER TABLE workout_exercise_snapshot",
                "replacement_snapshot_json JSON NULL",
                "replacement_revision BIGINT UNSIGNED NOT NULL DEFAULT 0",
                "idx_workout_session_user_started_id",
                "user_id, started_at DESC, id DESC");
        assertThat(readMigration("V015__progression_recommendation_lifecycle.sql")).contains(
                "accepted_weight DECIMAL(10,2)",
                "ck_progression_recommendation_decision_metadata",
                "uq_progression_recommendation_user_idempotency");
        assertThat(readMigration("V016__progression_decision_idempotency.sql")).contains(
                "progression_decision_idempotency",
                "payload_fingerprint CHAR(64)",
                "uq_progression_decision_user_operation_key",
                "idx_progression_recommendation_user_created_id");
        assertThat(readMigration("V017__workout_completion_outbox.sql")).contains(
                "workout_completion_outbox",
                "uq_workout_completion_outbox_session_event",
                "claim_token BINARY(16)",
                "idx_workout_completion_outbox_claim");
        assertThat(readMigration("V018__shared_privacy_operational_state.sql")).contains(
                "privacy_reauthentication_proof",
                "proof_digest VARBINARY(32)",
                "privacy_rate_limit_bucket",
                "PRIMARY KEY (user_id, action, bucket_start)");
        assertThat(readMigration("V019__workout_set_logical_void.sql")).contains(
                "CREATE TABLE workout_set_void",
                "uq_workout_set_void_set",
                "uq_workout_set_void_user_idempotency",
                "trg_workout_set_void_immutable_update",
                "trg_workout_set_void_immutable_delete");
        assertThat(readMigration("V020__workout_set_conflict_corrections.sql")).contains(
                "NEW.server_revision <> OLD.server_revision + 1",
                "workout_set_revision revision_fact",
                "revision_fact.revision_no = NEW.server_revision",
                "workout set correction requires revision audit");
        assertThat(readMigration("V022__workout_set_safety_flag.sql")).contains(
                "ADD COLUMN safety_flag VARCHAR(32) NULL",
                "ck_workout_set_safety_flag",
                "'PAIN', 'INJURY', 'CHEST_DISCOMFORT', 'DIZZINESS', 'SEVERE_UNWELL'",
                "NOT (NEW.safety_flag <=> OLD.safety_flag)",
                "NEW.server_revision <> OLD.server_revision + 1",
                "workout_set_revision revision_fact",
                "revision_fact.revision_no = NEW.server_revision",
                "workout set correction requires revision audit");
        assertThat(readMigration("V023__backfill_workout_set_idempotency.sql")).contains(
                "ROW_NUMBER() OVER",
                "ORDER BY snapshot.exercise_order, wset.set_order, wset.id",
                "legacy-workout-set-idempotency-v1|",
                "MODIFY COLUMN client_operation_seq BIGINT UNSIGNED NOT NULL",
                "MODIFY COLUMN payload_digest BINARY(32) NOT NULL",
                "MODIFY COLUMN applied_session_version BIGINT UNSIGNED NOT NULL",
                "ck_workout_set_client_operation_seq CHECK (client_operation_seq >= 1)",
                "NOT (NEW.safety_flag <=> OLD.safety_flag)",
                "workout set correction requires revision audit");
        assertThat(readMigration("V024__workout_recovery_confirmation.sql")).contains(
                "CREATE TABLE workout_recovery_confirmation",
                "token_digest VARBINARY(32) NOT NULL",
                "assessment_fingerprint VARBINARY(32) NOT NULL",
                "client_session_key VARCHAR(128) NOT NULL",
                "PRIMARY KEY (token_digest)",
                "consumed_at IS NULL OR consumed_at >= issued_at");
    }

    @Test
    void cleanMysql84DatabaseMigratesExactlyOnceAndValidates() {
        migrateEmptyMysqlDatabase();
        assertThat(flyway.info().applied())
                .extracting(MigrationInfo::getVersion)
                .extracting(Object::toString)
                .containsExactly(
                        "001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012",
                        "013", "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "024");
    }

    @Test
    void legacyWorkoutSetRowsUpgradeFromV011AndRemainJdbcReplayable() throws Exception {
        migrateEmptyMysqlDatabase();
        LegacyWorkoutSetUpgradeFixture fixture = Objects.requireNonNull(legacyWorkoutSetUpgradeFixture);
        JdbcWorkoutSetRepository repository = new JdbcWorkoutSetRepository(
                dataSource, new ObjectMapper().findAndRegisterModules());

        List<WorkoutSet> migrated = repository.findBySession(
                UUID.fromString(fixture.userId()), UUID.fromString(fixture.sessionId()));

        assertThat(migrated).extracting(WorkoutSet::clientSetKey)
                .containsExactly("legacy-set-1", "legacy-set-2");
        assertThat(migrated).extracting(WorkoutSet::clientOperationSeq)
                .containsExactly(1L, 2L);
        assertThat(migrated).extracting(WorkoutSet::payloadDigest)
                .containsExactly(legacyWorkoutSetDigest(fixture.firstSetId()),
                        legacyWorkoutSetDigest(fixture.secondSetId()));
        assertThat(repository.save(
                UUID.fromString(fixture.userId()), migrated.getFirst(), Long.MAX_VALUE))
                .satisfies(replay -> {
                    assertThat(replay.duplicate()).isTrue();
                    assertThat(replay.sessionVersion()).isEqualTo(2);
                });
        assertThat(queryOne("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'workout_set'
                  AND column_name IN ('client_operation_seq', 'payload_digest', 'applied_session_version')
                  AND is_nullable = 'NO'
                """)).isEqualTo("3");
    }

    @Test
    void databaseEnforcesSealedPlanAndImmutableWorkoutAndProgressionFacts() throws Exception {
        migrateEmptyMysqlDatabase();
        equipmentClientKeysAreScopedToTheirOwner();
        databaseEnforcesRequiredUniqueConstraintsAndCrossUserSessions();
        databaseAllowsBuildingThenSealingAndActivatingOnlyTheSamePlanVersion();
        databasePreservesTheOldPlanWhenANewVersionBecomesActive();
        databaseRejectsUnsealedSealingEntryPoints();
        databaseRejectsCrossPlanSnapshotsAndProtectsWorkoutFacts();
        progressionFactsAreImmutableAndAppliedPlanMustBelongToTheUser();
        databaseRejectsHistoricalDeletesAndRevisionRewrites();
    }

    @Test
    void retentionLifecycleRequiresPolicyExpiryHoldReleaseAndAuditsPayloadCleanup() throws Exception {
        migrateEmptyMysqlDatabase();
        String userId = createUser();
        String requestId = newId();
        String retentionId = newId();
        execute("INSERT INTO privacy_deletion_request "
                + "(id, user_id, status, requested_at, updated_at) VALUES ("
                + binary(requestId) + ", " + binary(userId)
                + ", 'RETENTION_SEPARATED', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))");

        assertRejected("45000", "retention expiry", () -> execute(
                retentionInsert(retentionId, requestId, "NULL")));
        execute(retentionInsert(retentionId, requestId, "UTC_TIMESTAMP(6) - INTERVAL 1 DAY"));
        assertRejected("45000", "invalid retention lifecycle transition", () -> execute(
                "UPDATE privacy_required_retention SET disposition_status='PURGED', "
                        + "disposed_at=UTC_TIMESTAMP(6), payload_digest=UNHEX(SHA2('', 256)) WHERE id="
                        + binary(retentionId)));

        execute("UPDATE privacy_required_retention SET hold_status='RELEASED', "
                + "hold_released_at=UTC_TIMESTAMP(6) WHERE id=" + binary(retentionId));
        execute("UPDATE privacy_required_retention SET disposition_status='PURGED', "
                + "disposed_at=UTC_TIMESTAMP(6), payload_digest=UNHEX(SHA2('', 256)) WHERE id="
                + binary(retentionId));

        assertThat(queryOne("SELECT disposition_status FROM privacy_required_retention WHERE id="
                + binary(retentionId))).isEqualTo("PURGED");
        assertThat(queryOne("SELECT COUNT(*) FROM privacy_retention_lifecycle_audit WHERE retention_id="
                + binary(retentionId))).isEqualTo("2");
        assertRejected("45000", "retention lifecycle audit is immutable", () -> execute(
                "DELETE FROM privacy_retention_lifecycle_audit WHERE retention_id=" + binary(retentionId)));
    }

    @Test
    void applicationRepositoryAtomicallyPersistsImmutablePlanVersionsAndRollsBackFailures() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID userId = UUID.fromString(createUser());
        ObjectMapper objectMapper = new ObjectMapper();
        ClasspathContentCatalogRepository catalogs =
                new ClasspathContentCatalogRepository(objectMapper);
        JdbcContentCatalogPublisher publisher =
                new JdbcContentCatalogPublisher(dataSource, objectMapper);
        publisher.publish(catalogs.exercises(), catalogs.templates());
        publisher.publish(catalogs.exercises(), catalogs.templates());
        UUID exerciseId = UUID.nameUUIDFromBytes(
                "ai-fitness-exercise:GOBLET_SQUAT".getBytes(StandardCharsets.UTF_8));
        assertThat(queryOne("SELECT COUNT(*) FROM exercise WHERE id="
                + binary(exerciseId.toString()))).isEqualTo("1");
        assertThat(queryOne("SELECT COUNT(*) FROM plan_template_version "
                + "WHERE template_code='FULL_BODY_3_DAY_V1'"))
                .isEqualTo("1");
        PlanDraft initial = planDraft("GOBLET_SQUAT", 90);
        PlanVersionService.PlanPolicy policy = new PlanVersionService.PlanPolicy() {
            @Override
            public PlanVersionService.CandidatePlan candidate(
                    AuthenticatedUserId user, String candidateId) {
                return new PlanVersionService.CandidatePlan(
                        candidateId, initial, new RuleReference("rule-v1", "template-v1", "content-v1"));
            }

            @Override
            public List<PlanVersionService.ValidationIssue> validate(
                    AuthenticatedUserId user, PlanDraft plan, RuleReference reference) {
                return List.of();
            }
        };
        JdbcPlanRepository repository = new JdbcPlanRepository(
                dataSource, objectMapper, ignored -> exerciseId);
        PlanVersionService service = new PlanVersionService(
                repository, policy, java.time.Clock.systemUTC());
        AuthenticatedUserId user = new AuthenticatedUserId(userId);

        var created = service.createInitial(user, "candidate-db");
        var updated = service.createVersion(
                user, created.id(), 1, planDraft("GOBLET_SQUAT", 120), Map.of(), null);

        assertThat(updated.version()).get().extracting(version -> version.versionNumber()).isEqualTo(2);
        assertThat(repository.findByIdAndUser(created.id(), userId).orElseThrow().version(1)
                .plan().valueAt("/days/DAY_A/exercises/GOBLET_SQUAT/restSeconds")).contains(90);
        assertThat(repository.findActiveByUser(userId).orElseThrow().activeVersion()
                .plan().valueAt("/days/DAY_A/exercises/GOBLET_SQUAT/restSeconds")).contains(120);
        assertThat(queryOne("SELECT COUNT(*) FROM training_plan_version WHERE plan_id = "
                + binary(created.id().toString()))).isEqualTo("2");

        JdbcPlanRepository failingRepository = new JdbcPlanRepository(
                dataSource, new ObjectMapper(), code -> {
                    throw new IllegalArgumentException("exercise code cannot be resolved");
                });
        assertThatThrownBy(() -> failingRepository.append(
                userId, created.id(), 2,
                new com.aifitness.assistant.plan.domain.TrainingPlanVersion(
                        UUID.randomUUID(), created.id(), 3,
                        com.aifitness.assistant.plan.domain.TrainingPlanVersion.SourceType.USER_EDIT,
                        planDraft("UNKNOWN", 150),
                        new RuleReference("rule-v1", "template-v1", "content-v1"),
                        java.util.Set.of(), java.time.Instant.now())))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(queryOne("SELECT COUNT(*) FROM training_plan_version WHERE plan_id = "
                + binary(created.id().toString()))).isEqualTo("2");
    }

    @Test
    void jdbcPlanCreationReplaysTheSameInitialPlanAndRejectsADifferentPlan() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID userId = UUID.fromString(createUser());
        ObjectMapper objectMapper = new ObjectMapper();
        ClasspathContentCatalogRepository catalogs =
                new ClasspathContentCatalogRepository(objectMapper);
        new JdbcContentCatalogPublisher(dataSource, objectMapper)
                .publish(catalogs.exercises(), catalogs.templates());
        UUID exerciseId = UUID.nameUUIDFromBytes(
                "ai-fitness-exercise:GOBLET_SQUAT".getBytes(StandardCharsets.UTF_8));
        JdbcPlanRepository repository = new JdbcPlanRepository(
                dataSource, objectMapper, ignored -> exerciseId);
        UUID planId = UUID.randomUUID();
        TrainingPlanVersion firstVersion = new TrainingPlanVersion(
                UUID.randomUUID(), planId, 1, TrainingPlanVersion.SourceType.INITIAL,
                planDraft("GOBLET_SQUAT", 90),
                new RuleReference("rule-v1", "template-v1", "content-v1"),
                java.util.Set.of(), java.time.Instant.now());
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);

        try {
            var firstCreate = executor.submit(() -> {
                start.await();
                return repository.create(userId, firstVersion);
            });
            var repeatedCreate = executor.submit(() -> {
                start.await();
                return repository.create(userId, firstVersion);
            });
            start.countDown();
            TrainingPlan first = firstCreate.get(10, TimeUnit.SECONDS);
            TrainingPlan replay = repeatedCreate.get(10, TimeUnit.SECONDS);

            assertThat(replay.id()).isEqualTo(first.id());
            assertThat(replay.activeVersion().id()).isEqualTo(first.activeVersion().id());
            assertThat(queryOne("SELECT COUNT(*) FROM training_plan WHERE user_id = "
                    + binary(userId.toString()) + " AND status = 'ACTIVE'")).isEqualTo("1");
            assertThat(queryOne("SELECT COUNT(*) FROM training_plan_version WHERE plan_id = "
                    + binary(planId.toString()))).isEqualTo("1");
        } finally {
            executor.shutdownNow();
        }

        UUID differentPlanId = UUID.randomUUID();
        TrainingPlanVersion differentFirstVersion = new TrainingPlanVersion(
                UUID.randomUUID(), differentPlanId, 1, TrainingPlanVersion.SourceType.INITIAL,
                planDraft("GOBLET_SQUAT", 90),
                new RuleReference("rule-v1", "template-v1", "content-v1"),
                java.util.Set.of(), java.time.Instant.now());
        assertThatThrownBy(() -> repository.create(userId, differentFirstVersion))
                .isInstanceOf(PlanVersionService.ActivePlanAlreadyExistsException.class);
        assertThat(queryOne("SELECT COUNT(*) FROM training_plan WHERE user_id = "
                + binary(userId.toString()) + " AND status = 'ACTIVE'")).isEqualTo("1");
    }

    @Test
    void jdbcIdentityAndSessionsPersistOnlyDigestsRotateAndBlockDeletedUsers() throws Exception {
        migrateEmptyMysqlDatabase();
        JdbcIdentityRepository identities = new JdbcIdentityRepository(dataSource);
        JdbcSessionStore sessions = new JdbcSessionStore(dataSource);
        byte[] protectedSubject = "protected-wechat-subject".getBytes(StandardCharsets.UTF_8);
        java.time.Instant now = java.time.Instant.parse("2026-07-24T06:00:00Z");

        AuthenticatedUserId user = identities.findOrCreate(
                UserIdentity.Provider.WECHAT_MINI_PROGRAM, protectedSubject, now);
        assertThat(identities.findOrCreate(
                UserIdentity.Provider.WECHAT_MINI_PROGRAM, protectedSubject.clone(), now))
                .isEqualTo(user);

        var issued = sessions.issue(user, now);
        assertThat(sessions.authenticate(issued.accessToken(), now.plusSeconds(1))).isEqualTo(user);
        assertThat(queryOne("SELECT COUNT(*) FROM auth_session WHERE access_token_digest = "
                + "UNHEX(SHA2('" + issued.accessToken() + "', 256))"))
                .isEqualTo("1");
        assertThat(queryOne("SELECT COUNT(*) FROM auth_session WHERE CAST(access_token_digest AS CHAR) = '"
                + issued.accessToken() + "'"))
                .isEqualTo("0");

        var refreshed = sessions.refresh(issued.refreshToken(), now.plusSeconds(2));
        assertThatThrownBy(() -> sessions.authenticate(issued.accessToken(), now.plusSeconds(3)))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatLoginService
                        .AuthenticationRequiredException.class);
        assertThatThrownBy(() -> sessions.refresh(issued.refreshToken(), now.plusSeconds(3)))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatLoginService
                        .AuthenticationRequiredException.class);
        assertThat(sessions.authenticate(refreshed.accessToken(), now.plusSeconds(3))).isEqualTo(user);

        sessions.revokeAllSessionsAndBlockLogin(user, UUID.randomUUID());
        assertThatThrownBy(() -> sessions.authenticate(refreshed.accessToken(), now.plusSeconds(4)))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatLoginService
                        .AccessRevokedException.class);
        assertThatThrownBy(() -> sessions.refresh(refreshed.refreshToken(), now.plusSeconds(4)))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatLoginService
                        .AccessRevokedException.class);
        assertThatThrownBy(() -> sessions.issue(user, now.plusSeconds(4)))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatLoginService
                        .AuthenticationRequiredException.class);
        assertThatThrownBy(() -> sessions.authenticate("unknown-access-token", now.plusSeconds(4)))
                .isInstanceOf(com.aifitness.assistant.identity.application.WechatLoginService
                        .AuthenticationRequiredException.class);
        assertThat(queryOne("SELECT status FROM user_account WHERE id="
                + binary(user.value().toString()))).isEqualTo("DELETED");
    }

    @Test
    void jdbcProfileRepositoryPreservesVersionsCollectionsAndUserIsolation() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID firstUserId = UUID.fromString(createUser());
        UUID secondUserId = UUID.fromString(createUser());
        UUID exerciseId = UUID.fromString(createExercise());
        JdbcProfileRepository profiles = new JdbcProfileRepository(dataSource, new ObjectMapper());

        UserProfile.Details details = new UserProfile.Details(
                UserProfile.ExperienceLevel.BEGINNER,
                UserProfile.FitnessGoal.GENERAL_FITNESS,
                3,
                45,
                UserProfile.TrainingLocation.HOME);
        assertThat(profiles.replaceProfile(firstUserId, 0, details).version()).isEqualTo(1);
        assertThat(profiles.findProfile(firstUserId)).isPresent();
        assertThat(profiles.findProfile(secondUserId)).isEmpty();
        assertThatThrownBy(() -> profiles.replaceProfile(firstUserId, 0, details))
                .isInstanceOfSatisfying(
                        ProfileService.VersionConflictException.class,
                        failure -> assertThat(failure.currentVersion()).isEqualTo(1));

        UUID clientEquipmentKey = UUID.randomUUID();
        EquipmentProfile equipment = profiles.replaceEquipment(firstUserId, 0, List.of(
                new EquipmentProfile.Item(
                        clientEquipmentKey,
                        "ADJUSTABLE_DUMBBELL",
                        new BigDecimal("2.50"),
                        "KG",
                        List.of(new BigDecimal("2.50"), new BigDecimal("5.00")))));
        assertThat(equipment.version()).isEqualTo(1);
        assertThat(profiles.findEquipment(firstUserId).orElseThrow().items())
                .extracting(EquipmentProfile.Item::clientEquipmentKey)
                .containsExactly(clientEquipmentKey);
        assertThat(profiles.findEquipment(secondUserId)).isEmpty();
        assertThatThrownBy(() -> profiles.replaceEquipment(firstUserId, 0, List.of()))
                .isInstanceOfSatisfying(
                        ProfileService.VersionConflictException.class,
                        failure -> assertThat(failure.currentVersion()).isEqualTo(1));

        PreferenceProfile preferences = profiles.replacePreferences(firstUserId, 0, List.of(
                new PreferenceProfile.Preference(
                        exerciseId, PreferenceProfile.PreferenceType.EXCLUDED)));
        assertThat(preferences.version()).isEqualTo(1);
        assertThat(profiles.findPreferences(firstUserId).orElseThrow().preferences())
                .extracting(PreferenceProfile.Preference::exerciseId)
                .containsExactly(exerciseId);
        assertThat(profiles.findPreferences(secondUserId)).isEmpty();
        assertThatThrownBy(() -> profiles.replacePreferences(firstUserId, 0, List.of()))
                .isInstanceOfSatisfying(
                        ProfileService.VersionConflictException.class,
                        failure -> assertThat(failure.currentVersion()).isEqualTo(1));
        assertThat(queryOne("SELECT COUNT(*) FROM user_equipment WHERE user_id="
                + binary(secondUserId.toString()))).isEqualTo("0");
        assertThat(queryOne("SELECT COUNT(*) FROM user_exercise_preference WHERE user_id="
                + binary(secondUserId.toString()))).isEqualTo("0");
    }

    @Test
    void jdbcPrivacyRepositoriesPersistOwnedArtifactsRequestsAndIdempotentAudit() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID firstUserId = UUID.fromString(createUser());
        UUID secondUserId = UUID.fromString(createUser());
        ObjectMapper objectMapper = new ObjectMapper();
        JdbcPrivacyRepository requests = new JdbcPrivacyRepository(dataSource);
        JdbcPrivacyExportRepository exports =
                new JdbcPrivacyExportRepository(dataSource, objectMapper,
                        java.time.Clock.fixed(
                                java.time.Instant.parse("2026-07-24T08:00:00Z"),
                                java.time.ZoneOffset.UTC));
        JdbcPrivacyAudit audit = new JdbcPrivacyAudit(dataSource, java.time.Clock.systemUTC());
        java.time.Instant now = java.time.Instant.parse("2026-07-24T08:00:00Z");

        DeletionRequest requested = new DeletionRequest(
                UUID.randomUUID(), firstUserId, DeletionRequest.Status.REQUESTED, now, now);
        assertThat(requests.save(requested)).isEqualTo(requested);
        assertThat(requests.findById(requested.id())).contains(requested);
        assertThat(requests.findActiveByUser(firstUserId)).contains(requested);
        assertThat(requests.findActiveByUser(secondUserId)).isEmpty();
        assertThatThrownBy(() -> requests.save(new DeletionRequest(
                requested.id(), secondUserId, DeletionRequest.Status.REQUESTED, now, now)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(requests.findById(requested.id())).contains(requested);

        execute("INSERT INTO user_profile (user_id, experience, goal, weekly_frequency, "
                + "session_minutes, location, version) VALUES ("
                + binary(firstUserId.toString())
                + ", 'BEGINNER', 'GENERAL_FITNESS', 3, 45, 'HOME', 1)");
        List<PrivacyDataPort.ResourceExport> firstUserExport =
                new JdbcPrivacyDataReader(dataSource).export(firstUserId);
        assertThat(firstUserExport)
                .filteredOn(resource -> resource.category() == PrivacyDataPort.Category.PROFILE)
                .singleElement()
                .satisfies(resource -> assertThat(resource.recordCount()).isEqualTo(1));
        assertThat(new JdbcPrivacyDataReader(dataSource).export(secondUserId))
                .allSatisfy(resource -> assertThat(resource.recordCount()).isZero());

        DeletionRequest advanced = requested.transitionTo(
                DeletionRequest.Status.ACCESS_REVOKED, now.plusSeconds(1));
        assertThat(requests.save(advanced)).isEqualTo(advanced);
        assertThat(requests.findById(requested.id())).contains(advanced);

        PrivacyExportRepository.ExportArtifact artifact = new PrivacyExportRepository.ExportArtifact(
                UUID.randomUUID(), firstUserId, "READY", now, now.plusSeconds(600),
                List.of(new PrivacyDataPort.ResourceExport(
                        PrivacyDataPort.Category.PROFILE,
                        List.of(new PrivacyDataPort.ExportRecord("profile", "成年用户训练档案")))),
                List.of("PROFILE"), List.of("SECURITY_AUDIT"));
        assertThat(exports.save(artifact)).isEqualTo(artifact);
        assertThat(exports.findById(artifact.id())).contains(artifact);
        assertThat(new JdbcPrivacyExportRepository(
                dataSource, objectMapper,
                java.time.Clock.fixed(now.plusSeconds(601), java.time.ZoneOffset.UTC))
                .findById(artifact.id())).isEmpty();
        assertThat(queryOne("SELECT COUNT(*) FROM privacy_export_artifact WHERE id="
                + binary(artifact.id().toString()))).isEqualTo("0");

        audit.recordStepOnce(firstUserId, "PRIVACY_ACCESS_REVOKED", requested.id());
        audit.recordStepOnce(firstUserId, "PRIVACY_ACCESS_REVOKED", requested.id());
        assertThat(queryOne("SELECT COUNT(*) FROM domain_audit WHERE user_id="
                + binary(firstUserId.toString())
                + " AND action='PRIVACY_ACCESS_REVOKED' AND entity_id="
                + binary(requested.id().toString()))).isEqualTo("1");
    }

    @Test
    void jdbcRecommendationRepositoryPersistsReplayEvidenceLifecycleAndOutboxAtomically() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID userId = UUID.fromString(createUser());
        UUID exerciseId = UUID.fromString(createExercise());
        PlanFixture plan = sealedPlan(userId.toString(), exerciseId.toString());
        UUID sessionId = UUID.fromString(createSession(plan, userId.toString()));
        ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
        JdbcRecommendationRepository repository = new JdbcRecommendationRepository(dataSource, mapper);
        RuleEvaluationInput.Progression signals = new RuleEvaluationInput.Progression(
                "double-progression-v1", RuleEvaluationInput.WeightUnit.KG, true, false, false, false,
                false, false, false, 0, false, true, 1, false, false, 2);
        ProgressionDecision.Prescription current = new ProgressionDecision.Prescription(
                new BigDecimal("40"), 8, 12);
        ProgressionEngine.EnginePolicy enginePolicy = new ProgressionEngine.EnginePolicy(
                "double-progression-v1", new BigDecimal("0.05"));
        EquipmentRoundingPolicy equipmentPolicy = new EquipmentRoundingPolicy(
                "KG", List.of(new BigDecimal("40"), new BigDecimal("42.5")));
        ProgressionDecision generated = new ProgressionEngine().evaluate(
                signals, current, enginePolicy, equipmentPolicy);
        String snapshotJson = mapper.writeValueAsString(Map.of(
                "schemaVersion", "progression-decision-snapshot-v1",
                "signals", signals,
                "equipmentStepsKg", equipmentPolicy.allowedSteps(),
                "reductionRate", enginePolicy.reductionRate()));
        ProgressionRecommendation recommendation = new ProgressionRecommendation(
                UUID.randomUUID(), userId, exerciseId, "GOBLET_SQUAT", sessionId,
                generated.decision(), generated.currentPrescription(), generated.recommendedPrescription(),
                generated.reasonCode().name(), snapshotJson, generated.algorithmVersion(),
                Optional.of(new ProgressionRecommendation.RoundingEvidence(
                        generated.rawRecommendedWeight().orElseThrow(), generated.roundedWeight().orElseThrow(),
                        generated.roundingRule().orElseThrow(), generated.availableEquipmentSteps())),
                ProgressionRecommendation.Status.PENDING,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                java.time.Instant.parse("2026-07-24T10:00:00Z"));

        ProgressionRecommendation persisted = repository.inTransaction(() -> {
            ProgressionRecommendation saved = repository.save(recommendation);
            repository.appendOutbox(recommendation.id(), "PROGRESSION_RECOMMENDATION_CREATED");
            return saved;
        });
        assertThat(repository.findByIdAndUser(recommendation.id(), userId)).contains(persisted);
        var storedSnapshot = mapper.readTree(persisted.inputSnapshotJson());
        RuleEvaluationInput.Progression replaySignals = mapper.treeToValue(
                storedSnapshot.get("signals"), RuleEvaluationInput.Progression.class);
        List<BigDecimal> replayEquipmentLevels = new java.util.ArrayList<>();
        storedSnapshot.withArray("equipmentStepsKg")
                .forEach(level -> replayEquipmentLevels.add(level.decimalValue()));
        ProgressionDecision replayed = new ProgressionEngine().evaluate(
                replaySignals, persisted.currentPrescription(), enginePolicy,
                new EquipmentRoundingPolicy("KG", replayEquipmentLevels));
        assertThat(replayed.decision()).isEqualTo(persisted.decision());
        assertThat(replayed.reasonCode().name()).isEqualTo(persisted.reasonCode());
        assertThat(replayed.recommendedPrescription()).isEqualTo(persisted.recommendedPrescription());
        assertThat(repository.saveIfAbsent(recommendation).created()).isFalse();
        assertThat(queryOne("SELECT COUNT(*) FROM progression_recommendation WHERE source_session_id = %s"
                .formatted(binary(sessionId.toString())))).isEqualTo("1");

        ProgressionRecommendation dismissed = persisted.dismiss("NOT_NOW");
        repository.inTransaction(() -> {
            repository.updatePending(dismissed, Optional.empty());
            repository.appendOutbox(recommendation.id(), "PROGRESSION_RECOMMENDATION_DISMISSED");
            return dismissed;
        });
        assertThat(repository.findByIdAndUser(recommendation.id(), userId).orElseThrow().status())
                .isEqualTo(ProgressionRecommendation.Status.DISMISSED);
        assertThat(queryOne("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = %s".formatted(
                binary(recommendation.id().toString())))).isEqualTo("2");
    }

    @Test
    void exerciseTrendQueryExcludesWarmupExtraAbortedAndAnomalousSets() throws Exception {
        migrateEmptyMysqlDatabase();
        String userId = createUser();
        String exerciseId = createExercise();
        PlanFixture plan = sealedPlan(userId, exerciseId);
        String sessionId = createSession(plan, userId);
        String snapshotId = newId();
        execute("""
                INSERT INTO workout_exercise_snapshot
                    (id, session_id, source_plan_exercise_id, source_training_day_id,
                     source_plan_version_id, exercise_order, exercise_snapshot_json,
                     prescription_snapshot_json, status)
                VALUES (%s, %s, %s, %s, %s, 1,
                    JSON_OBJECT('exerciseCode', 'GOBLET_SQUAT'), JSON_OBJECT(), 'ACTIVE')
                """.formatted(binary(snapshotId), binary(sessionId), binary(plan.planExerciseId()),
                binary(plan.dayId()), binary(plan.versionId())));
        execute("UPDATE workout_session SET status = 'COMPLETED', completed_at = '2026-07-24 09:00:00' WHERE id = "
                + binary(sessionId));
        insertTrendSet(snapshotId, "work-1", "WORK", 1, "40.0", 10, "COMPLETED", null);
        String voidedSetId = insertTrendSet(
                snapshotId, "work-2", "WORK", 2, "42.5", 8, "COMPLETED", null);
        insertTrendSet(snapshotId, "warmup", "WARMUP", 3, "20.0", 12, "COMPLETED", null);
        insertTrendSet(snapshotId, "extra", "EXTRA", 4, "50.0", 5, "COMPLETED", null);
        insertTrendSet(snapshotId, "anomaly", "WORK", 5, "100.0", 20, "COMPLETED", "CONFIRMED_EXCLUDED");
        insertTrendSet(snapshotId, "failed", "WORK", 6, "45.0", 0, "FAILED", null);
        insertTrendSet(
                snapshotId, "safety", "WORK", 7, "80.0", 12, "COMPLETED", null, "PAIN");
        execute("""
                INSERT INTO workout_set_void
                    (id, workout_set_id, session_id, user_id, idempotency_key, payload_digest,
                     reason, applied_session_version, voided_at)
                VALUES (%s, %s, %s, %s, 'trend-void-key', UNHEX(REPEAT('3', 64)),
                        'USER_REQUESTED', 1, '2026-07-24 08:45:00')
                """.formatted(binary(newId()), binary(voidedSetId), binary(sessionId), binary(userId)));

        String abortedSessionId = createSession(plan, userId);
        String abortedSnapshotId = newId();
        execute("""
                INSERT INTO workout_exercise_snapshot
                    (id, session_id, source_plan_exercise_id, source_training_day_id,
                     source_plan_version_id, exercise_order, exercise_snapshot_json,
                     prescription_snapshot_json, status)
                VALUES (%s, %s, %s, %s, %s, 1,
                    JSON_OBJECT('exerciseCode', 'GOBLET_SQUAT'), JSON_OBJECT(), 'ACTIVE')
                """.formatted(binary(abortedSnapshotId), binary(abortedSessionId), binary(plan.planExerciseId()),
                binary(plan.dayId()), binary(plan.versionId())));
        execute("UPDATE workout_session SET status = 'ABORTED', completed_at = '2026-07-24 10:00:00' WHERE id = "
                + binary(abortedSessionId));
        insertTrendSet(abortedSnapshotId, "aborted-work", "WORK", 1, "60.0", 12, "COMPLETED", null);

        var trend = new JdbcExerciseTrendQuery(dataSource).load(
                new AuthenticatedUserId(UUID.fromString(userId)), "GOBLET_SQUAT");

        assertThat(trend.points()).singleElement().satisfies(point -> {
            assertThat(point.sessionId()).isEqualTo(UUID.fromString(sessionId));
            assertThat(point.topWeightKg()).isEqualByComparingTo("40.0");
            assertThat(point.totalReps()).isEqualTo(10);
            assertThat(point.workSetCount()).isEqualTo(1);
        });

        WorkoutExerciseSnapshot currentExercise = new WorkoutExerciseSnapshot(
                UUID.fromString(snapshotId), UUID.fromString(sessionId), UUID.fromString(plan.planExerciseId()), 1,
                "GOBLET_SQUAT", "Goblet squat", "content-v1", java.util.Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(2, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.COMPLETED);
        var historicalFacts = new JdbcHistoricalProgressionFactProvider(dataSource).facts(
                new AuthenticatedUserId(UUID.fromString(userId)), currentExercise, List.of());
        assertThat(historicalFacts).hasSize(6).allSatisfy(fact -> {
            assertThat(fact.sessionId()).isEqualTo(UUID.fromString(sessionId));
            assertThat(fact.exerciseId()).isEqualTo(UUID.nameUUIDFromBytes(
                    "ai-fitness-exercise:GOBLET_SQUAT".getBytes(StandardCharsets.UTF_8)));
            assertThat(fact.exerciseId()).isNotEqualTo(UUID.fromString(plan.planExerciseId()));
            assertThat(fact.payloadDigest()).hasSize(64);
        });
        assertThat(historicalFacts).filteredOn(EffectiveSetSelector.RawSetFact::anomalous).hasSize(1);
        assertThat(historicalFacts)
                .filteredOn(fact -> fact.safetyFlag().isPresent())
                .singleElement()
                .satisfies(fact -> assertThat(fact.safetyFlag().orElseThrow())
                        .isEqualTo(ProgressionInput.SafetyFlag.PAIN));
    }

    @Test
    void jdbcWorkoutRepositoryPersistsOwnedSnapshotsAndOptimisticStatus() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID userId = UUID.fromString(createUser());
        UUID otherUserId = UUID.fromString(createUser());
        String exerciseId = createExercise();
        PlanFixture plan = createPlanFixture(userId.toString(), exerciseId);
        execute("INSERT INTO exercise_i18n (exercise_id, locale, name, instructions_json) VALUES (%s, 'zh-CN', '哑铃深蹲', JSON_OBJECT())"
                .formatted(binary(exerciseId)));
        execute("INSERT INTO exercise_equipment (exercise_id, equipment_type) VALUES (%s, 'DUMBBELL')"
                .formatted(binary(exerciseId)));
        execute("""
                UPDATE plan_exercise
                SET prescription_json = JSON_OBJECT(
                    'dayCode', 'DAY_1', 'exerciseCode', 'DB_SQUAT',
                    'workSets', 3, 'repMin', 8, 'repMax', 12, 'restSeconds', 90)
                WHERE id = %s
                """.formatted(binary(plan.planExerciseId())));
        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s"
                .formatted(binary(plan.versionId()), binary(plan.planId())));

        var source = new JdbcPlanWorkoutSnapshotQuery(dataSource, new ObjectMapper())
                .load(userId, UUID.fromString(plan.planId()), 1, "DAY_1");
        assertThat(source.planVersionId()).isEqualTo(UUID.fromString(plan.versionId()));
        assertThat(source.trainingDayId()).isEqualTo(UUID.fromString(plan.dayId()));
        assertThat(source.exercises()).singleElement().satisfies(exercise -> {
            assertThat(exercise.sourcePlanExerciseId())
                    .isEqualTo(UUID.fromString(plan.planExerciseId()));
            assertThat(exercise.exerciseCode()).isEqualTo("DB_SQUAT");
            assertThat(exercise.exerciseName()).isEqualTo("哑铃深蹲");
            assertThat(exercise.equipment()).containsExactly("DUMBBELL");
            assertThat(exercise.workSets()).isEqualTo(3);
            assertThat(exercise.repMin()).isEqualTo(8);
            assertThat(exercise.repMax()).isEqualTo(12);
            assertThat(exercise.restSeconds()).isEqualTo(90);
        });

        JdbcWorkoutSessionRepository repository =
                new JdbcWorkoutSessionRepository(dataSource, new ObjectMapper());
        UUID sessionId = UUID.randomUUID();
        WorkoutExerciseSnapshot snapshot = new WorkoutExerciseSnapshot(
                UUID.randomUUID(), sessionId, UUID.fromString(plan.planExerciseId()), 1,
                "DB_SQUAT", "哑铃深蹲", "content-v1", java.util.Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
        WorkoutSession created = new WorkoutSession(
                sessionId, userId, UUID.fromString(plan.planId()), UUID.fromString(plan.versionId()), 1,
                UUID.fromString(plan.dayId()), "DAY_1", "jdbc-session-key", WorkoutStatus.CREATED,
                java.time.Instant.parse("2026-07-24T08:00:00Z"), java.util.Optional.empty(), 0,
                List.of(snapshot),
                Optional.of(new WorkoutWarmupPrescription(
                        "workout-warmup-prescription-v1",
                        "1.3.0",
                        new WorkoutWarmupPrescription.GeneralWarmup(1, 180),
                        Optional.of(new WorkoutWarmupPrescription.RampWarmup(
                                snapshot.id(),
                                1,
                                WorkoutWarmupPrescription.RampStatus.READY,
                                Optional.of("DUMBBELL"),
                                List.of(
                                        new WorkoutWarmupPrescription.RampSet(new BigDecimal("10"), 10),
                                        new WorkoutWarmupPrescription.RampSet(new BigDecimal("15"), 6)),
                                Optional.empty(),
                                Optional.empty())),
                        false,
                        false)));

        assertThat(repository.create(created)).isEqualTo(created);
        assertThat(repository.create(created)).isEqualTo(created);
        assertThat(repository.findByUserAndClientKey(userId, "jdbc-session-key")).contains(created);
        assertThat(repository.findByIdAndUser(sessionId, otherUserId)).isEmpty();

        WorkoutSession active = created.transitionTo(
                WorkoutStatus.IN_PROGRESS, java.time.Instant.parse("2026-07-24T08:01:00Z"));
        assertThat(repository.update(active, 0)).isEqualTo(active);
        assertThatThrownBy(() -> repository.update(
                active.transitionTo(
                        WorkoutStatus.PAUSED, java.time.Instant.parse("2026-07-24T08:02:00Z")),
                0))
                .isInstanceOfSatisfying(
                        WorkoutSessionService.VersionConflictException.class,
                        failure -> assertThat(failure.currentVersion()).isEqualTo(1));
        assertThat(repository.findByIdAndUser(sessionId, userId)).contains(active);
        assertThat(queryOne("SELECT COUNT(*) FROM workout_exercise_snapshot WHERE session_id="
                + binary(sessionId.toString()))).isEqualTo("1");

        JdbcWorkoutSetRepository sets = new JdbcWorkoutSetRepository(dataSource, new ObjectMapper());
        WorkoutSet workoutSet = new WorkoutSet(
                UUID.randomUUID(), sessionId, snapshot.id(), "jdbc-set-key-001", 1,
                WorkoutSet.SetType.WORK, 1,
                new WorkoutSet.Performance(new BigDecimal("40"), "KG", 10),
                new WorkoutSet.Performance(new BigDecimal("40"), "KG", 9), 2,
                WorkoutSet.CompletionStatus.COMPLETED,
                java.util.Optional.of(java.time.Instant.parse("2026-07-24T08:03:00Z")),
                0, java.util.Optional.empty(), "0".repeat(64));
        var firstSet = sets.save(userId, workoutSet, 1);
        assertThat(firstSet.sessionVersion()).isEqualTo(2);
        assertThat(firstSet.duplicate()).isFalse();
        var duplicateSet = sets.save(userId, workoutSet, 1);
        assertThat(duplicateSet.set()).isEqualTo(firstSet.set());
        assertThat(duplicateSet.sessionVersion()).isEqualTo(firstSet.sessionVersion());
        assertThat(duplicateSet.duplicate()).isTrue();
        assertThat(queryOne("SELECT COUNT(*) FROM workout_set WHERE payload_digest IS NOT NULL"
                + " AND applied_session_version=2"
                + " AND client_set_key='jdbc-set-key-001'")).isEqualTo("1");
        WorkoutSet conflicting = new WorkoutSet(
                UUID.randomUUID(), sessionId, snapshot.id(), "jdbc-set-key-001", 1,
                WorkoutSet.SetType.WORK, 1, workoutSet.target(),
                new WorkoutSet.Performance(new BigDecimal("42"), "KG", 9), 2,
                WorkoutSet.CompletionStatus.COMPLETED, workoutSet.completedAt(), 0,
                java.util.Optional.empty(), "1".repeat(64));
        assertThatThrownBy(() -> sets.save(userId, conflicting, 2))
                .isInstanceOf(WorkoutSessionService.IdempotencyConflictException.class);

        var voided = sets.appendVoid(
                userId, sessionId, workoutSet.id(), "jdbc-set-void-001", "2".repeat(64), 2,
                UUID.randomUUID(), java.time.Instant.parse("2026-07-24T08:04:00Z"));
        assertThat(voided.sessionVersion()).isEqualTo(3);
        assertThat(voided.duplicate()).isFalse();
        assertThat(sets.appendVoid(
                userId, sessionId, workoutSet.id(), "jdbc-set-void-001", "2".repeat(64), 2,
                UUID.randomUUID(), java.time.Instant.parse("2026-07-24T08:04:01Z")))
                .satisfies(retry -> {
                    assertThat(retry.voidFact()).isEqualTo(voided.voidFact());
                    assertThat(retry.sessionVersion()).isEqualTo(3);
                    assertThat(retry.duplicate()).isTrue();
                });
        assertThat(sets.findBySession(userId, sessionId)).isEmpty();
        assertThat(sets.findById(userId, sessionId, workoutSet.id())).contains(workoutSet);
        assertThat(sets.findVoid(userId, sessionId, workoutSet.id())).contains(voided.voidFact());
        assertThat(queryOne("SELECT COUNT(*) FROM workout_set WHERE id=" + binary(workoutSet.id().toString())))
                .isEqualTo("1");
        assertThat(queryOne("SELECT COUNT(*) FROM workout_set_void WHERE workout_set_id="
                + binary(workoutSet.id().toString()))).isEqualTo("1");

        execute("""
                UPDATE workout_session
                SET status = 'COMPLETED', completed_at = '2026-07-24 08:05:00.000000', sync_version = 3
                WHERE id = %s AND user_id = %s
                """.formatted(binary(sessionId.toString()), binary(userId.toString())));
        var history = new JdbcWorkoutHistoryRepository(dataSource).findHistory(
                userId, Optional.empty(), Optional.empty(), 50);
        assertThat(history).singleElement().satisfies(item -> {
            assertThat(item.sessionId()).isEqualTo(sessionId);
            assertThat(item.trainingDayCode()).isEqualTo("DAY_1");
            assertThat(item.trainingDayName()).isEqualTo("Day 1");
            assertThat(item.completedWorkSets()).isZero();
            assertThat(item.completedVolumeKg()).isEqualByComparingTo("0");
            assertThat(item.completedReps()).isZero();
            assertThat(item.usesExternalLoad()).isFalse();
        });

        java.time.Clock conflictClock = java.time.Clock.fixed(
                java.time.Instant.parse("2026-07-24T08:04:00Z"), java.time.ZoneOffset.UTC);
        JdbcSyncConflictRepository conflicts = new JdbcSyncConflictRepository(
                dataSource, new ObjectMapper(), conflictClock);
        SyncConflict open = conflicts.save(new SyncConflict(
                UUID.randomUUID(), userId, "WORKOUT_SET", "jdbc-set-key-001",
                Map.of("actualReps", "8"), Map.of("actualReps", "9"), SyncConflict.Status.OPEN,
                java.util.Optional.empty(), 0, conflictClock.instant(), java.util.Optional.empty()));
        assertThat(conflicts.listOpen(userId)).containsExactly(open);
        assertThat(conflicts.listOpen(UUID.randomUUID())).isEmpty();
        SyncConflict resolved = conflicts.resolve(
                userId, open.id(), SyncConflict.Resolution.KEEP_SERVER, 0);
        assertThat(resolved.status()).isEqualTo(SyncConflict.Status.RESOLVED);
        assertThat(resolved.version()).isEqualTo(1);
        assertThat(conflicts.listOpen(userId)).isEmpty();
        assertThatThrownBy(() -> conflicts.resolve(
                userId, open.id(), SyncConflict.Resolution.KEEP_LOCAL, 0))
                .isInstanceOf(WorkoutSessionService.VersionConflictException.class);
    }

    @Test
    void jdbcWorkoutReplacementPersistsAndReloadsTheEffectivePrescription() throws Exception {
        migrateEmptyMysqlDatabase();
        UUID userId = UUID.fromString(createUser());
        String exerciseId = createExercise();
        PlanFixture plan = createPlanFixture(userId.toString(), exerciseId);
        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s"
                .formatted(binary(plan.versionId()), binary(plan.planId())));

        JdbcWorkoutSessionRepository repository =
                new JdbcWorkoutSessionRepository(dataSource, new ObjectMapper());
        UUID sessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        WorkoutExerciseSnapshot source = new WorkoutExerciseSnapshot(
                snapshotId, sessionId, UUID.fromString(plan.planExerciseId()), 1,
                "DUMBBELL_PRESS", "哑铃推举", "content-v1", Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(
                        3, 8, 12, 90, "KNOWN", Optional.of(new BigDecimal("18")), "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
        WorkoutSession created = new WorkoutSession(
                sessionId, userId, UUID.fromString(plan.planId()), UUID.fromString(plan.versionId()), 1,
                UUID.fromString(plan.dayId()), "DAY_1", "jdbc-replacement-" + sessionId,
                WorkoutStatus.CREATED, Instant.parse("2026-07-24T09:00:00Z"), Optional.empty(), 0,
                List.of(source));
        repository.create(created);
        repository.update(created.transitionTo(
                WorkoutStatus.IN_PROGRESS, Instant.parse("2026-07-24T09:01:00Z")), 0);

        WorkoutExerciseSnapshot replacement = new WorkoutExerciseSnapshot(
                snapshotId, sessionId, source.sourcePlanExerciseId(), 1,
                "PUSH_UP", "俯卧撑", "content-v2", Set.of("BODYWEIGHT"),
                source.prescription().forReplacement(source.equipment(), Set.of("BODYWEIGHT")),
                WorkoutExerciseSnapshot.Status.REPLACED);
        WorkoutSession replaced = repository.replaceExercise(
                userId, sessionId, snapshotId, 1, replacement);

        assertThat(replaced.version()).isEqualTo(2);
        assertThat(replaced.exercises()).singleElement().satisfies(exercise -> {
            assertThat(exercise.exerciseCode()).isEqualTo("PUSH_UP");
            assertThat(exercise.prescription().weightStatus()).isEqualTo("BODYWEIGHT");
            assertThat(exercise.prescription().targetWeightKg()).isEmpty();
            assertThat(exercise.prescription().workSets()).isEqualTo(3);
            assertThat(exercise.prescription().repMin()).isEqualTo(8);
            assertThat(exercise.prescription().repMax()).isEqualTo(12);
            assertThat(exercise.prescription().restSeconds()).isEqualTo(90);
        });
        assertThat(repository.findByIdAndUser(sessionId, userId)).contains(replaced);
        assertThat(queryOne("SELECT JSON_UNQUOTE(JSON_EXTRACT(replacement_snapshot_json, '$.prescription.weightStatus'))"
                + " FROM workout_exercise_snapshot WHERE id=" + binary(snapshotId.toString())))
                .isEqualTo("BODYWEIGHT");
        assertThat(queryOne("SELECT JSON_EXTRACT(replacement_snapshot_json, '$.prescription.targetWeightKg')"
                + " FROM workout_exercise_snapshot WHERE id=" + binary(snapshotId.toString())))
                .isNull();

        execute("""
                UPDATE workout_exercise_snapshot
                SET replacement_snapshot_json = JSON_OBJECT(
                    'exerciseCode', 'PUSH_UP', 'exerciseName', '俯卧撑',
                    'contentVersion', 'content-v1', 'equipment', JSON_ARRAY('BODYWEIGHT')),
                    replacement_revision = replacement_revision + 1
                WHERE id = %s
                """.formatted(binary(snapshotId.toString())));
        WorkoutExerciseSnapshot.Prescription legacyPrescription = repository
                .findByIdAndUser(sessionId, userId).orElseThrow()
                .exercises().getFirst().prescription();
        assertThat(legacyPrescription.weightStatus()).isEqualTo("BODYWEIGHT");
        assertThat(legacyPrescription.targetWeightKg()).isEmpty();

        assertJdbcReplacementPrescription(
                repository, userId, plan, Set.of("DUMBBELL"), Set.of("DUMBBELL"),
                "KNOWN", Optional.of(new BigDecimal("18")), "KNOWN", Optional.of(new BigDecimal("18")));
        assertJdbcReplacementPrescription(
                repository, userId, plan, Set.of("BODYWEIGHT"), Set.of("DUMBBELL"),
                "BODYWEIGHT", Optional.empty(), "NEEDS_CALIBRATION", Optional.empty());
        assertJdbcReplacementPrescription(
                repository, userId, plan, Set.of("DUMBBELL"), Set.of("CABLE"),
                "KNOWN", Optional.of(new BigDecimal("18")), "NEEDS_CALIBRATION", Optional.empty());
    }

    private static void assertJdbcReplacementPrescription(
            JdbcWorkoutSessionRepository repository,
            UUID userId,
            PlanFixture plan,
            Set<String> sourceEquipment,
            Set<String> replacementEquipment,
            String sourceWeightStatus,
            Optional<BigDecimal> sourceTargetWeight,
            String expectedWeightStatus,
            Optional<BigDecimal> expectedTargetWeight) {
        UUID sessionId = UUID.randomUUID();
        UUID snapshotId = UUID.randomUUID();
        WorkoutExerciseSnapshot.Prescription sourcePrescription =
                new WorkoutExerciseSnapshot.Prescription(
                        3, 8, 12, 90, sourceWeightStatus, sourceTargetWeight, "KG");
        WorkoutExerciseSnapshot source = new WorkoutExerciseSnapshot(
                snapshotId, sessionId, UUID.fromString(plan.planExerciseId()), 1,
                "SOURCE_" + snapshotId.toString().substring(0, 8), "原动作", "content-v1",
                sourceEquipment, sourcePrescription, WorkoutExerciseSnapshot.Status.PENDING);
        WorkoutSession created = new WorkoutSession(
                sessionId, userId, UUID.fromString(plan.planId()), UUID.fromString(plan.versionId()), 1,
                UUID.fromString(plan.dayId()), "DAY_1", "jdbc-replacement-" + sessionId,
                WorkoutStatus.CREATED, Instant.parse("2026-07-24T10:00:00Z"), Optional.empty(), 0,
                List.of(source));
        repository.create(created);
        repository.update(created.transitionTo(
                WorkoutStatus.IN_PROGRESS, Instant.parse("2026-07-24T10:01:00Z")), 0);
        WorkoutExerciseSnapshot replacement = new WorkoutExerciseSnapshot(
                snapshotId, sessionId, source.sourcePlanExerciseId(), 1,
                "REPLACEMENT_" + snapshotId.toString().substring(0, 8), "替代动作", "content-v2",
                replacementEquipment,
                sourcePrescription.forReplacement(sourceEquipment, replacementEquipment),
                WorkoutExerciseSnapshot.Status.REPLACED);

        WorkoutExerciseSnapshot persisted = repository.replaceExercise(
                userId, sessionId, snapshotId, 1, replacement).exercises().getFirst();

        assertThat(persisted.prescription().weightStatus()).isEqualTo(expectedWeightStatus);
        assertThat(persisted.prescription().targetWeightKg()).isEqualTo(expectedTargetWeight);
        assertThat(repository.findByIdAndUser(sessionId, userId).orElseThrow()
                .exercises().getFirst().prescription()).isEqualTo(persisted.prescription());
    }

    private static PlanDraft planDraft(String exerciseCode, int restSeconds) {
        return new PlanDraft(
                "FULL_BODY_3D", "全身训练",
                List.of(new PlanDraft.Day(
                        "DAY_A", "训练 A",
                        List.of(new PlanDraft.Exercise(
                                exerciseCode, 3, 8, 12, restSeconds,
                                PlanDraft.WeightStatus.NEEDS_CALIBRATION)))),
                Map.of());
    }

    private static void databasePreservesTheOldPlanWhenANewVersionBecomesActive() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        PlanFixture first = createPlanFixture(userId, exerciseId);
        execute("UPDATE plan_exercise SET prescription_json = JSON_OBJECT('restSeconds', 90) WHERE id = %s"
                .formatted(binary(first.planExerciseId())));
        seal(first);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s"
                .formatted(binary(first.versionId()), binary(first.planId())));

        String secondVersionId = newId();
        String secondDayId = newId();
        String secondExerciseId = newId();
        execute("INSERT INTO training_plan_version (id, plan_id, version_no, source_type, split_type, frequency, template_version, rule_version, change_summary_json, created_at) VALUES (%s, %s, 2, 'USER_EDIT', 'FULL_BODY', 3, 'template-v1', 'rule-v1', JSON_OBJECT('confirmedWarnings', JSON_ARRAY('INITIAL_WEIGHT_NEEDS_CALIBRATION')), UTC_TIMESTAMP(6))"
                .formatted(binary(secondVersionId), binary(first.planId())));
        execute("INSERT INTO training_day (id, plan_version_id, day_order, name, estimated_minutes) VALUES (%s, %s, 1, 'Day 1', 45)"
                .formatted(binary(secondDayId), binary(secondVersionId)));
        execute("INSERT INTO plan_exercise (id, training_day_id, plan_version_id, exercise_id, exercise_order, prescription_json, weight_status, status) VALUES (%s, %s, %s, %s, 1, JSON_OBJECT('restSeconds', 120), 'KNOWN', 'ACTIVE')"
                .formatted(binary(secondExerciseId), binary(secondDayId), binary(secondVersionId), binary(exerciseId)));
        execute("INSERT INTO plan_field_lock (plan_exercise_id, field_path, lock_status, locked_at) VALUES (%s, '/days/DAY_A/exercises/SQUAT/restSeconds', 'USER_LOCKED', UTC_TIMESTAMP(6))"
                .formatted(binary(secondExerciseId)));
        execute("UPDATE training_plan_version SET sealed_at = UTC_TIMESTAMP(6) WHERE id = %s"
                .formatted(binary(secondVersionId)));
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s"
                .formatted(binary(secondVersionId), binary(first.planId())));

        assertThat(queryOne("SELECT JSON_UNQUOTE(JSON_EXTRACT(prescription_json, '$.restSeconds')) FROM plan_exercise WHERE id = %s"
                .formatted(binary(first.planExerciseId())))).isEqualTo("90");
        assertThat(queryOne("SELECT JSON_UNQUOTE(JSON_EXTRACT(prescription_json, '$.restSeconds')) FROM plan_exercise WHERE id = %s"
                .formatted(binary(secondExerciseId)))).isEqualTo("120");
        assertThat(queryOne("SELECT version_no FROM training_plan_version WHERE id = (SELECT active_version_id FROM training_plan WHERE id = %s)"
                .formatted(binary(first.planId())))).isEqualTo("2");
    }

    private static void equipmentClientKeysAreScopedToTheirOwner() throws Exception {
        String firstUser = createUser();
        String secondUser = createUser();
        String sharedClientKey = newId();
        execute("INSERT INTO user_equipment (id, user_id, client_equipment_key, equipment_type, min_increment, unit) VALUES (%s, %s, %s, 'DUMBBELL', 2.50, 'KG')"
                .formatted(binary(newId()), binary(firstUser), binary(sharedClientKey)));
        execute("INSERT INTO user_equipment (id, user_id, client_equipment_key, equipment_type, min_increment, unit) VALUES (%s, %s, %s, 'DUMBBELL', 2.50, 'KG')"
                .formatted(binary(newId()), binary(secondUser), binary(sharedClientKey)));
        assertRejected("23000", null, () -> execute(
                "INSERT INTO user_equipment (id, user_id, client_equipment_key, equipment_type, min_increment, unit) VALUES (%s, %s, %s, 'BARBELL', 2.50, 'KG')"
                        .formatted(binary(newId()), binary(firstUser), binary(sharedClientKey))));
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
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, client_operation_seq, set_type, set_order, target_json, unit, completion_status, server_revision, payload_digest, applied_session_version) VALUES (%s, %s, 'set-key', 1, 'WORKING', 1, JSON_OBJECT(), 'KG', 'PENDING', 0, UNHEX(SHA2('set-key-1', 256)), 1)".formatted(binary(newId()), binary(snapshotId)));
        assertRejected(1062, "uq_workout_set_client_key", () ->
                execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, client_operation_seq, set_type, set_order, target_json, unit, completion_status, server_revision, payload_digest, applied_session_version) VALUES (%s, %s, 'set-key', 2, 'WORKING', 2, JSON_OBJECT(), 'KG', 'PENDING', 0, UNHEX(SHA2('set-key-2', 256)), 2)".formatted(binary(newId()), binary(snapshotId))));
        assertRejected(3819, "ck_workout_set_client_operation_seq", () ->
                execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, client_operation_seq, set_type, set_order, target_json, unit, completion_status, server_revision, payload_digest, applied_session_version) VALUES (%s, %s, 'zero-operation-seq', 0, 'WORKING', 2, JSON_OBJECT(), 'KG', 'PENDING', 0, UNHEX(SHA2('zero-operation-seq', 256)), 2)".formatted(binary(newId()), binary(snapshotId))));
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
        assertThat(queryOne("SELECT applied_plan_id FROM progression_recommendation WHERE id = %s".formatted(binary(recommendationId)))).isNull();
        assertRejected("45000", "progression recommendation must start unapplied", () ->
                execute("INSERT INTO progression_recommendation (id, user_id, exercise_id, source_session_id, decision, current_json, recommended_json, reason_code, input_snapshot_json, algorithm_version, user_decision, applied_plan_id, applied_plan_version_id, created_at) VALUES (%s, %s, %s, %s, 'INCREASE', JSON_OBJECT(), JSON_OBJECT(), 'TARGET_REPS_MET', JSON_OBJECT(), 'pre-applied-v1', 'PENDING', %s, %s, UTC_TIMESTAMP(6))".formatted(binary(newId()), binary(userId), binary(exerciseId), binary(sourceSessionId), binary(unsealedPlan.planId()), binary(unsealedPlan.versionId()))));
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
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, client_operation_seq, set_type, set_order, target_json, unit, completion_status, server_revision, payload_digest, applied_session_version) VALUES (%s, %s, 'set-key', 1, 'WORKING', 1, JSON_OBJECT('reps', 8), 'KG', 'PENDING', 0, UNHEX(SHA2('set-key', 256)), 1)".formatted(binary(setId), binary(snapshotId)));
        assertRejected("45000", "workout set facts are immutable", () ->
                execute("UPDATE workout_set SET target_json = JSON_OBJECT('reps', 10) WHERE id = %s".formatted(binary(setId))));
        assertRejected("45000", "workout set correction requires revision audit", () ->
                execute("UPDATE workout_set SET actual_reps = 8, completion_status = 'COMPLETED', server_revision = 1, payload_digest = UNHEX(SHA2('corrected-set', 256)), applied_session_version = 1 WHERE id = %s"
                        .formatted(binary(setId))));
        execute("INSERT INTO workout_set_revision (id, workout_set_id, revision_no, before_json, after_json, reason, created_at) VALUES (%s, %s, 1, JSON_OBJECT('actualReps', NULL), JSON_OBJECT('actualReps', 8), 'SYNC_CONFLICT_KEEP_LOCAL', UTC_TIMESTAMP(6))"
                .formatted(binary(newId()), binary(setId)));
        execute("UPDATE workout_set SET actual_reps = 8, completion_status = 'COMPLETED', server_revision = 1, payload_digest = UNHEX(SHA2('corrected-set', 256)), applied_session_version = 1 WHERE id = %s"
                .formatted(binary(setId)));
        assertRejected("45000", "workout set correction requires revision audit", () ->
                execute("UPDATE workout_set SET safety_flag = 'PAIN', server_revision = 2 WHERE id = %s"
                        .formatted(binary(setId))));
        execute("INSERT INTO workout_set_revision (id, workout_set_id, revision_no, before_json, after_json, reason, created_at) VALUES (%s, %s, 2, JSON_OBJECT('safetyFlag', NULL), JSON_OBJECT('safetyFlag', 'PAIN'), 'SYNC_CONFLICT_KEEP_LOCAL', UTC_TIMESTAMP(6))"
                .formatted(binary(newId()), binary(setId)));
        execute("UPDATE workout_set SET safety_flag = 'PAIN', server_revision = 2, payload_digest = UNHEX(SHA2('corrected-set-with-pain', 256)), applied_session_version = 2 WHERE id = %s"
                .formatted(binary(setId)));
        assertThat(queryOne("SELECT safety_flag FROM workout_set WHERE id = %s".formatted(binary(setId))))
                .isEqualTo("PAIN");
        assertRejected(3819, "ck_workout_set_safety_flag", () ->
                execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, client_operation_seq, set_type, set_order, target_json, unit, completion_status, server_revision, safety_flag, payload_digest, applied_session_version) VALUES (%s, %s, 'invalid-safety-set', 2, 'WORKING', 2, JSON_OBJECT('reps', 8), 'KG', 'FAILED', 0, 'DISCOMFORT', UNHEX(SHA2('invalid-safety-set', 256)), 2)"
                        .formatted(binary(newId()), binary(snapshotId))));

        PlanFixture otherPlan = createPlanFixture(userId, exerciseId);
        seal(otherPlan);
        assertRejected(1452, "fk_workout_snapshot_plan_source", () ->
                execute(snapshotInsert(
                        newId(), sessionId, otherPlan.planExerciseId(), plan.dayId(), plan.versionId(), 2)));

        String otherUserId = createUser();
        PlanFixture otherUserPlan = createPlanFixture(otherUserId, exerciseId);
        seal(otherUserPlan);
        assertRejected(1452, "fk_workout_snapshot_plan_source", () ->
                execute(snapshotInsert(
                        newId(), sessionId, otherUserPlan.planExerciseId(), plan.dayId(), plan.versionId(), 2)));
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

        execute("UPDATE progression_recommendation SET user_decision = 'APPLIED', accepted_weight = 42.50, decision_idempotency_key = 'migration-apply-once', decided_at = UTC_TIMESTAMP(6), applied_plan_id = %s, applied_plan_version_id = %s WHERE id = %s".formatted(binary(plan.planId()), binary(plan.versionId()), binary(recommendationId)));
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
        execute("INSERT INTO workout_set (id, session_exercise_id, client_set_key, client_operation_seq, set_type, set_order, target_json, unit, completion_status, server_revision, payload_digest, applied_session_version) VALUES (%s, %s, 'delete-set', 1, 'WORKING', 1, JSON_OBJECT(), 'KG', 'PENDING', 0, UNHEX(SHA2('delete-set', 256)), 1)".formatted(binary(setId), binary(setSnapshotId)));
        assertRejected("45000", "workout set history is immutable", () ->
                execute("DELETE FROM workout_set WHERE id = %s".formatted(binary(setId))));
        String voidId = newId();
        execute("""
                INSERT INTO workout_set_void
                    (id, workout_set_id, session_id, user_id, idempotency_key, payload_digest,
                     reason, applied_session_version, voided_at)
                VALUES (%s, %s, %s, %s, 'migration-void-key', UNHEX(REPEAT('0', 64)),
                        'USER_REQUESTED', 1, UTC_TIMESTAMP(6))
                """.formatted(binary(voidId), binary(setId), binary(setSessionId), binary(userId)));
        assertRejected("45000", "workout set void facts are immutable", () ->
                execute("UPDATE workout_set_void SET reason = 'USER_REQUESTED' WHERE id = %s"
                        .formatted(binary(voidId))));
        assertRejected("45000", "workout set void facts are immutable", () ->
                execute("DELETE FROM workout_set_void WHERE id = %s".formatted(binary(voidId))));

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
        if (flyway != null) {
            return;
        }
        dataSource = selectDataSource();
        Flyway preIdempotency = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("011")
                .load();
        assertThat(preIdempotency.migrate().migrationsExecuted).isEqualTo(11);
        try {
            legacyWorkoutSetUpgradeFixture = insertLegacyWorkoutSetsBeforeV012();
        } catch (Exception exception) {
            throw new IllegalStateException("legacy workout set upgrade fixture cannot be created", exception);
        }
        flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(13);
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        if (externalValidationJdbcUrl != null) {
            try (Connection connection = dataSource.getConnection()) {
                ExternalMysqlValidationMarker.write(
                        System.getProperty(VALIDATION_MARKER_PROPERTY),
                        System.getProperty(VERIFICATION_RUN_ID_PROPERTY),
                        externalValidationJdbcUrl,
                        connection);
            } catch (SQLException ignored) {
                throw new IllegalArgumentException("External MySQL validation marker cannot be created");
            }
        }
    }

    private static LegacyWorkoutSetUpgradeFixture insertLegacyWorkoutSetsBeforeV012() throws Exception {
        String userId = createUser();
        String exerciseId = createExercise();
        PlanFixture plan = createPlanFixture(userId, exerciseId);
        seal(plan);
        execute("UPDATE training_plan SET active_version_id = %s WHERE id = %s"
                .formatted(binary(plan.versionId()), binary(plan.planId())));
        String sessionId = createSession(plan, userId);
        String snapshotId = newId();
        execute(snapshotInsert(snapshotId, sessionId, plan));
        String firstSetId = newId();
        String secondSetId = newId();
        execute(legacyWorkoutSetInsert(secondSetId, snapshotId, "legacy-set-2", 2));
        execute(legacyWorkoutSetInsert(firstSetId, snapshotId, "legacy-set-1", 1));
        execute("UPDATE workout_session SET status = 'COMPLETED', completed_at = '2026-07-24 08:30:00', sync_version = 2 WHERE id = %s"
                .formatted(binary(sessionId)));
        return new LegacyWorkoutSetUpgradeFixture(
                userId, sessionId, firstSetId, secondSetId);
    }

    private static String legacyWorkoutSetInsert(
            String setId, String snapshotId, String clientSetKey, int setOrder) {
        return """
                INSERT INTO workout_set
                    (id, session_exercise_id, client_set_key, set_type, set_order, target_json,
                     actual_weight, unit, actual_reps, remaining_reps, completion_status,
                     completed_at, server_revision, anomaly_status)
                VALUES (%s, %s, '%s', 'WORK', %s,
                        JSON_OBJECT('weight', 40, 'unit', 'KG', 'reps', 8),
                        40, 'KG', 8, 2, 'COMPLETED', '2026-07-24 08:30:00', 0, NULL)
                """.formatted(binary(setId), binary(snapshotId), clientSetKey, setOrder);
    }

    private static String legacyWorkoutSetDigest(String setId) throws Exception {
        String stableIdentity = "legacy-workout-set-idempotency-v1|" + setId.replace("-", "");
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(stableIdentity.getBytes(StandardCharsets.UTF_8)));
    }

    private static DataSource selectDataSource() {
        String externalJdbcUrl = System.getenv(JDBC_URL_ENVIRONMENT);
        if (externalJdbcUrl != null) {
            suppressExternalFlywayTopologyLogs();
            ExternalMysqlValidationMarker.clear(System.getProperty(VALIDATION_MARKER_PROPERTY));
            DataSource externalDataSource = externalDataSource(
                    externalJdbcUrl,
                    System.getenv(USERNAME_ENVIRONMENT),
                    externalPassword(),
                    Boolean.getBoolean(ALLOW_REMOTE_PROPERTY),
                    Boolean.getBoolean(ALLOW_UNVERIFIED_TLS_PROPERTY),
                    Boolean.getBoolean(ALLOW_PINNED_CA_PROPERTY),
                    System.getenv(TRUST_STORE_ENVIRONMENT),
                    System.getenv(TRUST_STORE_TYPE_ENVIRONMENT),
                    externalTrustStorePassword());
            validateExternalDatabase(externalDataSource);
            externalValidationJdbcUrl = ((MysqlDataSource) externalDataSource).getUrl();
            return externalDataSource;
        }

        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required only when no external MySQL 8.0 test database is configured");
        MYSQL.start();
        return mysqlDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static DataSource externalDataSource(String jdbcUrl, String username, String password) {
        return externalDataSource(jdbcUrl, username, password, false);
    }

    private static String externalPassword() {
        return ExternalTestSecret.read(
                System.getenv(PASSWORD_ENVIRONMENT),
                System.getenv(PASSWORD_FILE_ENVIRONMENT),
                "External MySQL password");
    }

    private static void suppressExternalFlywayTopologyLogs() {
        if (flywayLogLevelOverridden) return;
        Logger flywayLogger = (Logger) LoggerFactory.getLogger("org.flywaydb");
        previousFlywayLogLevel = flywayLogger.getLevel();
        flywayLogger.setLevel(Level.WARN);
        flywayLogLevelOverridden = true;
    }

    private static String externalTrustStorePassword() {
        return ExternalTestSecret.read(
                System.getenv(TRUST_STORE_PASSWORD_ENVIRONMENT),
                System.getenv(TRUST_STORE_PASSWORD_FILE_ENVIRONMENT),
                "External MySQL trust store password");
    }

    private static DataSource externalDataSource(
            String jdbcUrl, String username, String password, boolean allowRemote) {
        return externalDataSource(
                jdbcUrl, username, password, allowRemote, false, false, null, null, null);
    }

    private static DataSource externalDataSource(
            String jdbcUrl,
            String username,
            String password,
            boolean allowRemote,
            boolean allowPinnedCa,
            String trustStorePath,
            String trustStoreType,
            String trustStorePassword) {
        return externalDataSource(
                jdbcUrl,
                username,
                password,
                allowRemote,
                false,
                allowPinnedCa,
                trustStorePath,
                trustStoreType,
                trustStorePassword);
    }

    private static DataSource externalDataSource(
            String jdbcUrl,
            String username,
            String password,
            boolean allowRemote,
            boolean allowUnverifiedTls,
            boolean allowPinnedCa,
            String trustStorePath,
            String trustStoreType,
            String trustStorePassword) {
        if (jdbcUrl == null || jdbcUrl.isBlank()) {
            throw new IllegalArgumentException("External MySQL JDBC URL must be configured");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("External MySQL username must be configured");
        }
        if (!jdbcUrl.startsWith("jdbc:mysql://")) {
            throw new IllegalArgumentException("External test database must use a MySQL JDBC URL");
        }

        URI uri;
        try {
            uri = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (URISyntaxException ignored) {
            throw new IllegalArgumentException("External MySQL JDBC URL is invalid");
        }
        if (uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            throw new IllegalArgumentException(
                    "External MySQL JDBC URL must not include user-info, query, or fragment");
        }
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "External MySQL JDBC URL must target exactly one host and port");
        }
        boolean loopback = isLoopbackHost(uri.getHost());
        if (!allowRemote && !loopback) {
            throw new IllegalArgumentException("External test database host must be loopback");
        }
        if (!loopback && !isIpLiteral(uri.getHost())) {
            throw new IllegalArgumentException("External test database remote host must be an IP literal");
        }
        ExternalMysqlDatabaseTarget.requireAllowed(uri, EXTERNAL_DATABASE_NAME_ERROR);
        if (allowUnverifiedTls && allowPinnedCa) {
            throw new IllegalArgumentException("External MySQL TLS modes are mutually exclusive");
        }
        ExternalMysqlTls.Configuration tls = loopback
                ? null
                : allowUnverifiedTls
                        ? ExternalMysqlTls.encryptedWithoutIdentityVerification(jdbcUrl)
                        : allowPinnedCa
                        ? ExternalMysqlTls.pinnedCa(
                                jdbcUrl, trustStorePath, trustStoreType, trustStorePassword)
                        : ExternalMysqlTls.verifiedIdentity(jdbcUrl);
        return mysqlDataSource(jdbcUrl, username, password, tls);
    }

    private static DataSource mysqlDataSource(String jdbcUrl, String username, String password) {
        return mysqlDataSource(jdbcUrl, username, password, null);
    }

    private static DataSource mysqlDataSource(
            String jdbcUrl,
            String username,
            String password,
            ExternalMysqlTls.Configuration tls) {
        MysqlDataSource mysqlDataSource = new MysqlDataSource();
        if (tls == null) mysqlDataSource.setURL(jdbcUrl);
        else tls.configure(mysqlDataSource);
        mysqlDataSource.setUser(username);
        mysqlDataSource.setPassword(password == null ? "" : password);
        return mysqlDataSource;
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) {
            return false;
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost")) {
            try {
                return java.util.Arrays.stream(java.net.InetAddress.getAllByName(normalizedHost))
                        .allMatch(java.net.InetAddress::isLoopbackAddress);
            } catch (java.net.UnknownHostException exception) {
                return false;
            }
        }
        if (normalizedHost.equals("::1") || normalizedHost.equals("[::1]")) return true;
        String[] octets = normalizedHost.split("\\.", -1);
        if (octets.length != 4 || !octets[0].equals("127")) {
            return false;
        }
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            } catch (NumberFormatException exception) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpLiteral(String host) {
        if (host == null || host.isBlank() || host.contains("%")) return false;
        String[] octets = host.split("\\.", -1);
        if (octets.length == 4) {
            for (String octet : octets) {
                if (octet.isEmpty() || !octet.chars().allMatch(Character::isDigit)) return false;
                int value;
                try {
                    value = Integer.parseInt(octet);
                } catch (NumberFormatException ignored) {
                    return false;
                }
                if (value < 0 || value > 255) return false;
            }
            return true;
        }
        if (!host.contains(":")) return false;
        try {
            return java.net.InetAddress.getByName(host) instanceof java.net.Inet6Address;
        } catch (java.net.UnknownHostException ignored) {
            return false;
        }
    }

    private static void validateExternalDatabase(DataSource externalDataSource) {
        try (Connection connection = externalDataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            validateExternalServerVersion(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseMajorVersion(),
                    metadata.getDatabaseMinorVersion());
            String expectedDatabase = ExternalMysqlDatabaseTarget.requireAllowed(
                    URI.create(((MysqlDataSource) externalDataSource).getUrl().substring("jdbc:".length())),
                    EXTERNAL_DATABASE_NAME_ERROR);
            if (!expectedDatabase.equals(connection.getCatalog())) {
                throw new IllegalArgumentException(EXTERNAL_DATABASE_NAME_ERROR);
            }
            if (requiresEncryptedTls(externalDataSource)) {
                assertEncryptedConnection(connection);
            }
            try (var statement = connection.createStatement();
                 ResultSet tables = statement.executeQuery(
                         "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()")) {
                if (!tables.next() || tables.getInt(1) != 0) {
                    throw new IllegalArgumentException("External test database must be empty");
                }
            }
        } catch (SQLException ignored) {
            throw new IllegalArgumentException("Unable to validate external test database");
        }
    }

    private static void validateExternalServerVersion(
            String productName,
            int majorVersion,
            int minorVersion) {
        if (!"MySQL".equalsIgnoreCase(productName) || majorVersion != 8) {
            throw new IllegalArgumentException("External test database server must be MySQL 8");
        }
    }

    private static boolean requiresEncryptedTls(DataSource dataSource) {
        return dataSource instanceof MysqlDataSource mysqlDataSource
                && mysqlDataSource.getUrl() != null
                && (mysqlDataSource.getUrl().endsWith("?sslMode=VERIFY_IDENTITY")
                        || mysqlDataSource.getUrl().endsWith("?sslMode=VERIFY_CA")
                        || mysqlDataSource.getUrl().endsWith("?sslMode=REQUIRED"));
    }

    private static void assertEncryptedConnection(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                ResultSet status = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            if (!status.next() || status.getString(2) == null || status.getString(2).isBlank()) {
                throw new IllegalArgumentException(
                        "External test database connection must use TLS");
            }
        }
    }

    private static void assertThrowableChainDoesNotContain(Throwable throwable, String forbiddenText) {
        for (Throwable current = throwable; current != null; current = current.getCause()) {
            assertThat(current.getMessage()).doesNotContain(forbiddenText);
        }
    }

    private static String insertTrendSet(
            String snapshotId,
            String clientKey,
            String setType,
            int setOrder,
            String weight,
            int reps,
            String status,
            String anomalyStatus) throws Exception {
        return insertTrendSet(
                snapshotId, clientKey, setType, setOrder, weight, reps, status, anomalyStatus, null);
    }

    private static String insertTrendSet(
            String snapshotId,
            String clientKey,
            String setType,
            int setOrder,
            String weight,
            int reps,
            String status,
            String anomalyStatus,
            String safetyFlag) throws Exception {
        String anomaly = anomalyStatus == null ? "NULL" : "'" + anomalyStatus + "'";
        String safety = safetyFlag == null ? "NULL" : "'" + safetyFlag + "'";
        String completedAt = status.equals("COMPLETED") ? "'2026-07-24 08:30:00'" : "NULL";
        String setId = newId();
        execute("""
                INSERT INTO workout_set
                    (id, session_exercise_id, client_set_key, client_operation_seq,
                     set_type, set_order, target_json,
                     actual_weight, unit, actual_reps, completion_status, completed_at,
                     server_revision, safety_flag, anomaly_status, payload_digest, applied_session_version)
                VALUES (%s, %s, '%s', %s, '%s', %s, JSON_OBJECT(), %s, 'KG', %s, '%s', %s,
                        0, %s, %s, UNHEX(SHA2('%s', 256)), %s)
                """.formatted(binary(setId), binary(snapshotId), clientKey, setOrder, setType, setOrder,
                weight, reps, status, completedAt, safety, anomaly, clientKey, setOrder));
        return setId;
    }

    private static String createUser() throws Exception {
        String userId = newId();
        execute("INSERT INTO user_account (id, status, created_at) VALUES (%s, 'ACTIVE', UTC_TIMESTAMP(6))".formatted(binary(userId)));
        return userId;
    }

    private static String createExercise() throws Exception {
        String exerciseId = newId();
        createExercise(exerciseId);
        return exerciseId;
    }

    private static void createExercise(String exerciseId) throws Exception {
        execute("INSERT INTO exercise (id, measurement_type, movement_pattern, difficulty, status, content_version, review_status) VALUES (%s, 'WEIGHTED', 'SQUAT', 'BEGINNER', 'ACTIVE', 'v1', 'AI_VALIDATED')".formatted(binary(exerciseId)));
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
        return snapshotInsert(snapshotId, sessionId, sourcePlanExerciseId, sourceDayId, sourceVersionId, 1);
    }

    private static String snapshotInsert(
            String snapshotId,
            String sessionId,
            String sourcePlanExerciseId,
            String sourceDayId,
            String sourceVersionId,
            int exerciseOrder) {
        return "INSERT INTO workout_exercise_snapshot (id, session_id, source_plan_exercise_id, source_training_day_id, source_plan_version_id, exercise_order, exercise_snapshot_json, prescription_snapshot_json, status) VALUES (%s, %s, %s, %s, %s, %s, JSON_OBJECT(), JSON_OBJECT(), 'ACTIVE')"
                .formatted(
                        binary(snapshotId),
                        binary(sessionId),
                        binary(sourcePlanExerciseId),
                        binary(sourceDayId),
                        binary(sourceVersionId),
                        exerciseOrder);
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

    private static String retentionInsert(String retentionId, String requestId, String retainedUntil) {
        return "INSERT INTO privacy_required_retention "
                + "(id, deletion_request_id, user_reference_digest, retention_category, "
                + "payload_digest, retained_until, policy_version, created_at) VALUES ("
                + binary(retentionId) + ", " + binary(requestId)
                + ", UNHEX(SHA2('user', 256)), 'SECURITY_AUDIT', UNHEX(SHA2('payload', 256)), "
                + retainedUntil + ", 'fixture-policy-v1', UTC_TIMESTAMP(6))";
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
        try (Connection connection = dataSource.getConnection();
             var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static String queryOne(String sql) throws Exception {
        try (Connection connection = dataSource.getConnection();
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
    static void stopMysqlContainerAndRestoreLogging() {
        if (MYSQL.isRunning()) {
            MYSQL.stop();
        }
        if (flywayLogLevelOverridden) {
            ((Logger) LoggerFactory.getLogger("org.flywaydb"))
                    .setLevel(previousFlywayLogLevel);
            flywayLogLevelOverridden = false;
        }
    }

    private record PlanFixture(String planId, String versionId, String dayId, String planExerciseId) {
    }

    private record LegacyWorkoutSetUpgradeFixture(
            String userId,
            String sessionId,
            String firstSetId,
            String secondSetId) {
    }
}
