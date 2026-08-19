package com.aifitness.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import com.mysql.cj.jdbc.MysqlDataSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalMysqlTlsTest {
    @TempDir
    Path tempDirectory;

    @Test
    void pinnedCaRequiresARegularTrustStoreAndSecretWithoutLeakingThem() throws Exception {
        Path trustStore = tempDirectory.resolve("mysql-validation.p12");
        Files.writeString(trustStore, "test-only-placeholder");
        var configuration = ExternalMysqlTls.pinnedCa(
                "jdbc:mysql://database.internal:3306/fitness_m0",
                trustStore.toString(),
                "PKCS12",
                "trust-store-secret-marker");

        MysqlDataSource dataSource = new MysqlDataSource();
        configuration.configure(dataSource);

        assertThat(dataSource.getUrl())
                .isEqualTo("jdbc:mysql://database.internal:3306/fitness_m0?sslMode=VERIFY_CA");
        assertThat(dataSource.getSslMode()).isEqualTo("VERIFY_CA");
        assertThat(dataSource.getTrustCertificateKeyStoreUrl()).isEqualTo(trustStore.toUri().toString());
        assertThat(dataSource.getTrustCertificateKeyStoreType()).isEqualTo("PKCS12");
        assertThat(dataSource.getFallbackToSystemTrustStore()).isFalse();
        assertThat(configuration.toString())
                .doesNotContain(trustStore.toString(), "trust-store-secret-marker");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlTls.pinnedCa(
                        "jdbc:mysql://database.internal:3306/fitness_m0",
                        tempDirectory.resolve("missing.p12").toString(),
                        "PKCS12",
                        "trust-store-secret-marker"))
                .withMessage("Pinned-CA trust store must be a regular non-symlink file")
                .withMessageNotContaining("trust-store-secret-marker");
    }

    @Test
    void springChildReceivesEquivalentPinnedCaSettingsWithoutSystemTrustFallback() throws Exception {
        Path trustStore = tempDirectory.resolve("mysql-validation.jks");
        Files.writeString(trustStore, "test-only-placeholder");
        var configuration = ExternalMysqlTls.pinnedCa(
                "jdbc:mysql://database.internal:3306/fitness_m0",
                trustStore.toString(),
                "JKS",
                "trust-store-secret-marker");
        var environment = new HashMap<String, String>();

        configuration.applyToSpringEnvironment(environment);

        String springJson = environment.get("SPRING_APPLICATION_JSON");
        assertThat(springJson)
                .contains("trustCertificateKeyStoreUrl", "fallbackToSystemTrustStore", "false")
                .contains("trust-store-secret-marker");
        assertThat(configuration.jdbcUrl()).endsWith("?sslMode=VERIFY_CA");
    }

    @Test
    void secretFilesAreSingleSourceSingleLineAndTrailingNewlineCompatible() throws Exception {
        Path secret = tempDirectory.resolve("database-password.txt");
        Files.writeString(secret, "database-secret-marker\r\n");

        assertThat(ExternalTestSecret.read(null, secret.toString(), "Database password"))
                .isEqualTo("database-secret-marker");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalTestSecret.read(
                        "environment-secret-marker", secret.toString(), "Database password"))
                .withMessage("Database password must use either an environment value or a secret file")
                .withMessageNotContaining("environment-secret-marker")
                .withMessageNotContaining("database-secret-marker");

        Files.writeString(secret, "first-line\nsecond-line");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalTestSecret.read(null, secret.toString(), "Database password"))
                .withMessage("Database password secret file must contain exactly one line");
    }
}
