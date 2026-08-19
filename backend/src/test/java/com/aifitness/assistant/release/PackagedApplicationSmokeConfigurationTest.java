package com.aifitness.assistant.release;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PackagedApplicationSmokeConfigurationTest {

    @TempDir
    Path tempDirectory;

    @Test
    void remoteDatabaseRequiresExplicitOptInAndExactDisposableSchema() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                        "root",
                        "secret-marker",
                        false))
                .withMessage("External packaged-smoke database host must be loopback")
                .withMessageNotContaining("secret-marker");

        assertThat(PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                        "root",
                        "secret-marker",
                        true)
                .jdbcUrl())
                .isEqualTo("jdbc:mysql://192.0.2.10:3306/fitness_m0?sslMode=VERIFY_IDENTITY");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://127.0.0.1:3306/mysql",
                        "root",
                        "secret-marker",
                        false))
                .withMessage(
                        "External packaged-smoke database must use an approved disposable name")
                .withMessageNotContaining("secret-marker");
    }

    @Test
    void packagedSmokeAllowsOnlyStrictlyNamedDisposableSchemas() {
        assertThat(PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://192.0.2.10:3306/fitness_verify_20260815a1b2",
                        "root",
                        "secret-marker",
                        true)
                .jdbcUrl())
                .isEqualTo(
                        "jdbc:mysql://192.0.2.10:3306/fitness_verify_20260815a1b2"
                                + "?sslMode=VERIFY_IDENTITY");

        for (String database : java.util.List.of(
                "fitness_verify_short",
                "fitness_verify_20260815-A1B2",
                "fitness_verify_20260815a1b2_extra_suffix_that_is_too_long",
                "production")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                            "jdbc:mysql://192.0.2.10:3306/" + database,
                            "root",
                            "secret-marker",
                            true))
                    .withMessage(
                            "External packaged-smoke database must use an approved disposable name")
                    .withMessageNotContaining("secret-marker");
        }
    }

    @Test
    void pinnedCaRequiresACompleteLocalTrustStoreAndDoesNotExposeItsSecret() throws Exception {
        Path trustStore = tempDirectory.resolve("mysql-validation.p12");
        Files.writeString(trustStore, "test-only-placeholder");

        var database = PackagedApplicationSmokeIT.externalDatabase(
                "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                "root",
                "database-secret-marker",
                true,
                true,
                trustStore.toString(),
                "PKCS12",
                "trust-store-secret-marker");

        assertThat(database.jdbcUrl())
                .isEqualTo("jdbc:mysql://192.0.2.10:3306/fitness_m0?sslMode=VERIFY_CA");
        assertThat(database.tls().sslMode()).isEqualTo("VERIFY_CA");
        assertThat(database.tls().toString())
                .doesNotContain(trustStore.toString(), "trust-store-secret-marker");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                        "root",
                        "database-secret-marker",
                        true,
                        true,
                        null,
                        "PKCS12",
                        "trust-store-secret-marker"))
                .withMessage("Pinned-CA validation requires a trust store file")
                .withMessageNotContaining("database-secret-marker")
                .withMessageNotContaining("trust-store-secret-marker");
    }

    @Test
    void encryptedOnlyModeRequiresExplicitOptInAndRejectsConflictingTlsModes() throws Exception {
        var database = PackagedApplicationSmokeIT.externalDatabase(
                "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                "root",
                "database-secret-marker",
                true,
                true,
                false,
                null,
                null,
                null);

        assertThat(database.jdbcUrl())
                .isEqualTo("jdbc:mysql://192.0.2.10:3306/fitness_m0?sslMode=REQUIRED");
        assertThat(database.tls().sslMode()).isEqualTo("REQUIRED");

        Path trustStore = tempDirectory.resolve("conflicting-mysql-validation.p12");
        Files.writeString(trustStore, "test-only-placeholder");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                        "root",
                        "database-secret-marker",
                        true,
                        true,
                        true,
                        trustStore.toString(),
                        "PKCS12",
                        "trust-store-secret-marker"))
                .withMessage("External packaged-smoke TLS modes are mutually exclusive")
                .withMessageNotContaining("database-secret-marker")
                .withMessageNotContaining("trust-store-secret-marker");
    }

    @Test
    void loopbackValidationRejectsHostnamesThatOnlyStartWith127() {
        for (String host : new String[] {
                "127.attacker.example", "127.0.0.1.evil", "127.0.0.999", "127.0.one.1"
        }) {
            assertThatThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                            "jdbc:mysql://" + host + ":3306/fitness_m0",
                            "root",
                            "secret-marker",
                            false))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageNotContaining("secret-marker");
        }

        assertThat(PackagedApplicationSmokeIT.externalDatabase(
                        "jdbc:mysql://127.255.255.255:3306/fitness_m0",
                        "root",
                        "secret-marker",
                        false)
                .jdbcUrl())
                .isEqualTo("jdbc:mysql://127.255.255.255:3306/fitness_m0");
    }

    @Test
    void externalDatabaseRejectsMultiHostAndMissingPortForms() {
        for (String jdbcUrl : java.util.List.of(
                "jdbc:mysql://approved.internal:3306,other.internal:3307/fitness_m0",
                "jdbc:mysql://approved.internal/fitness_m0",
                "jdbc:mysql://address=(host=approved.internal)(port=3306)/fitness_m0")) {
            assertThatIllegalArgumentException()
                    .isThrownBy(() -> PackagedApplicationSmokeIT.externalDatabase(
                            jdbcUrl, "root", "secret-marker", true))
                    .withMessage(
                            "External packaged-smoke JDBC URL must target exactly one host and port")
                    .withMessageNotContaining("secret-marker");
        }
    }

    @Test
    void mysql8IsTheSupportedBaselineAndNeverAcceptsMysql9OrMariaDb() {
        PackagedApplicationSmokeIT.validateExternalServerVersion("MySQL", 8, 0);
        PackagedApplicationSmokeIT.validateExternalServerVersion("MySQL", 8, 4);

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackagedApplicationSmokeIT.validateExternalServerVersion(
                        "MySQL", 9, 0))
                .withMessage("External packaged-smoke database server must be MySQL 8");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> PackagedApplicationSmokeIT.validateExternalServerVersion(
                        "MariaDB", 8, 0))
                .withMessage("External packaged-smoke database server must be MySQL 8");
    }

    @Test
    void persistedSmokeLogRedactsJdbcUrlUsernameAndPassword() throws Exception {
        Path log = tempDirectory.resolve("packaged-smoke.log");
        Path trustStore = tempDirectory.resolve("mysql-validation.p12");
        Files.writeString(trustStore, "test-only-placeholder");
        Files.writeString(
                log,
                "Access denied for user 'remote_test_user'@'database.internal'. "
                        + "Database jdbc:mysql://192.0.2.10:3306/fitness_m0?sslMode=VERIFY_CA "
                        + "username=remote_test_user password=secret-marker "
                        + trustStore.toUri() + " trust-store-secret-marker");
        var database = PackagedApplicationSmokeIT.externalDatabase(
                "jdbc:mysql://192.0.2.10:3306/fitness_m0",
                "remote_test_user",
                "secret-marker",
                true,
                true,
                trustStore.toString(),
                "PKCS12",
                "trust-store-secret-marker");

        assertThatThrownBy(() -> PackagedApplicationSmokeIT.assertStagingProfileLogged(log))
                .isInstanceOf(AssertionError.class)
                .hasMessageNotContaining("database.internal")
                .hasMessageNotContaining("remote_test_user")
                .hasMessageNotContaining("secret-marker");
        PackagedApplicationSmokeIT.sanitizeLog(log, database);

        assertThat(Files.readString(log))
                .contains("jdbc:mysql://[redacted]")
                .doesNotContain(
                        "database.internal",
                        "remote_test_user",
                        "secret-marker",
                        trustStore.toUri().toString(),
                        "trust-store-secret-marker");
    }

    @Test
    void interruptedCleanupWaitsForForcedProcessExitAndRestoresInterrupt() {
        InterruptingProcess process = new InterruptingProcess();

        try {
            Thread.currentThread().interrupt();
            assertThatThrownBy(() -> PackagedApplicationSmokeIT.stopApplication(process))
                    .isInstanceOf(InterruptedException.class);

            assertThat(process.destroyForciblyCalled).isTrue();
            assertThat(process.isAlive()).isFalse();
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    private static final class InterruptingProcess extends Process {
        private boolean alive = true;
        private boolean destroyForciblyCalled;

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            if (Thread.interrupted()) {
                throw new InterruptedException("test interruption");
            }
            alive = false;
            return true;
        }

        @Override
        public int exitValue() {
            if (alive) throw new IllegalThreadStateException("process is alive");
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            destroyForciblyCalled = true;
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }
    }
}
