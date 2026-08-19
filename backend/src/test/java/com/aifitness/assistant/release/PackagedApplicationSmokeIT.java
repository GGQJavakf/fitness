package com.aifitness.assistant.release;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.infrastructure.JdbcSessionStore;
import com.aifitness.assistant.testsupport.ExternalMysqlTls;
import com.aifitness.assistant.testsupport.ExternalMysqlDatabaseTarget;
import com.aifitness.assistant.testsupport.ExternalTestSecret;
import com.aifitness.assistant.testsupport.ExternalMysqlValidationMarker;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.jdbc.MysqlDataSource;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;

class PackagedApplicationSmokeIT {
    private static final String JDBC_URL_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_JDBC_URL";
    private static final String USERNAME_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_USERNAME";
    private static final String PASSWORD_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_PASSWORD";
    private static final String PASSWORD_FILE_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_PASSWORD_FILE";
    private static final String ALLOW_REMOTE_PROPERTY = "fitness.smoke.mysql.allow-remote";
    private static final String ALLOW_UNVERIFIED_TLS_PROPERTY =
            "fitness.smoke.mysql.allow-unverified-tls";
    private static final String ALLOW_PINNED_CA_PROPERTY = "fitness.smoke.mysql.allow-pinned-ca";
    private static final String TRUST_STORE_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_TRUST_STORE";
    private static final String TRUST_STORE_TYPE_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_TRUST_STORE_TYPE";
    private static final String TRUST_STORE_PASSWORD_ENVIRONMENT = "FITNESS_SMOKE_MYSQL_TRUST_STORE_PASSWORD";
    private static final String TRUST_STORE_PASSWORD_FILE_ENVIRONMENT =
            "FITNESS_SMOKE_MYSQL_TRUST_STORE_PASSWORD_FILE";
    private static final String EXTERNAL_DATABASE_NAME_ERROR =
            "External packaged-smoke database must use an approved disposable name";
    private static final String VERIFICATION_RUN_ID_PROPERTY = "fitness.verification.run-id";
    private static final String VALIDATION_MARKER_PROPERTY = "fitness.verification.marker";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @Test
    void packagedJarStartsWithStagingProfileAndServesTheConsumerContract() throws Exception {
        Path jar = Path.of(System.getProperty("packaged.application.jar"));
        assertThat(jar).isRegularFile();
        try (SmokeDatabase database = selectDatabase()) {
            verifyPackagedApplication(jar, database);
        }
    }

    private static void verifyPackagedApplication(Path jar, SmokeDatabase database) throws Exception {
        int port = freePort();
        Path log = Path.of("target", "packaged-application-smoke.log");
        Process application = startApplication(jar, log, port, database);
        try {
            JsonNode health = waitForHealth(port, application, log);
            assertThat(health.at("/status").asText()).isEqualTo("UP");
            assertThat(health.at("/components/db/status").asText()).isEqualTo("UP");
            HttpResponse<String> beansResponse = get(port, "/actuator/beans", null);
            assertThat(beansResponse.statusCode()).isEqualTo(200);
            JsonNode beans = JSON.readTree(beansResponse.body());
            assertThat(beans.at("/contexts/application/beans/dataSource/type").asText())
                    .contains("DataSource");
            assertThat(beans.at("/contexts/application/beans/flyway/type").asText())
                    .contains("Flyway");

            DataSource dataSource = dataSource(database);
            JdbcTemplate jdbc = new JdbcTemplate(dataSource);
            assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM flyway_schema_history WHERE success = 1", Integer.class))
                    .isGreaterThan(0);

            String accessToken = issueAccessToken(jdbc, dataSource);
            JsonNode fixture = JSON.readTree(Path.of(
                    "..", "contract", "consumer-samples", "exercise-list.json").toFile());
            String path = fixture.at("/request/path").asText();

            HttpResponse<String> anonymous = get(port, path, null);
            assertThat(anonymous.statusCode()).isEqualTo(401);

            HttpResponse<String> authenticated = get(
                    port, path, accessToken, "https://public-browser.example");
            assertThat(authenticated.statusCode()).isEqualTo(fixture.at("/expectedStatus").asInt());
            assertThat(authenticated.headers().firstValue("content-type").orElse(""))
                    .startsWith("application/json");
            assertThat(authenticated.headers().firstValue("access-control-allow-origin")).isEmpty();
            JsonNode response = JSON.readTree(authenticated.body());
            for (JsonNode pointer : fixture.withArray("requiredResponsePointers")) {
                assertThat(response.at(pointer.asText()).isMissingNode())
                        .as("runtime response pointer %s", pointer.asText())
                        .isFalse();
            }

            assertStagingProfileLogged(log);
        } finally {
            try {
                stopApplication(application);
            } finally {
                sanitizeLog(log, database);
            }
        }
    }

    private static Process startApplication(
            Path jar, Path log, int port, SmokeDatabase database) throws IOException {
        Files.deleteIfExists(log);
        String java = Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java")
                .toString();
        ProcessBuilder builder = new ProcessBuilder(List.of(
                java, "-jar", jar.toAbsolutePath().toString(),
                "--server.address=127.0.0.1",
                "--server.port=" + port,
                "--management.endpoints.web.exposure.include=health,beans",
                "--management.endpoint.health.show-components=always"));
        builder.environment().put("SPRING_PROFILES_ACTIVE", "staging-experience");
        builder.environment().put("FITNESS_DB_URL", database.jdbcUrl());
        builder.environment().put("FITNESS_DB_USERNAME", database.username());
        builder.environment().put("FITNESS_DB_PASSWORD", database.password());
        if (database.tls() != null) database.tls().applyToSpringEnvironment(builder.environment());
        builder.environment().put("WECHAT_APP_ID", "wx1234567890abcdef");
        builder.environment().put("WECHAT_APP_SECRET", "packaged-smoke-placeholder");
        return builder.redirectErrorStream(true).redirectOutput(log.toFile()).start();
    }

    private static JsonNode waitForHealth(int port, Process process, Path log) throws Exception {
        URI uri = URI.create("http://127.0.0.1:" + port + "/actuator/health");
        Instant deadline = Instant.now().plusSeconds(90);
        Exception lastFailure = null;
        while (Instant.now().isBefore(deadline)) {
            if (!process.isAlive()) {
                throw new AssertionError(
                        "packaged application exited early; inspect sanitized log at "
                                + log.toAbsolutePath());
            }
            try {
                HttpResponse<String> response = HTTP.send(
                        HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) return JSON.readTree(response.body());
            } catch (IOException | InterruptedException exception) {
                lastFailure = exception;
                if (exception instanceof InterruptedException) Thread.currentThread().interrupt();
            }
            Thread.sleep(250);
        }
        throw new AssertionError("packaged application health did not become ready", lastFailure);
    }

    private static String issueAccessToken(JdbcTemplate jdbc, DataSource dataSource) {
        UUID userId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO user_account (id, status, created_at) VALUES (?, 'ACTIVE', ?)",
                bytes(userId), Timestamp.from(Instant.now()));
        return new JdbcSessionStore(dataSource)
                .issue(new AuthenticatedUserId(userId), Instant.now())
                .accessToken();
    }

    private static HttpResponse<String> get(int port, String path, String accessToken)
            throws IOException, InterruptedException {
        return get(port, path, accessToken, null);
    }

    private static HttpResponse<String> get(int port, String path, String accessToken, String origin)
            throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(
                URI.create("http://127.0.0.1:" + port + path))
                .timeout(Duration.ofSeconds(5))
                .GET();
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        if (origin != null) request.header("Origin", origin);
        return HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static DataSource dataSource(SmokeDatabase database) {
        MysqlDataSource dataSource = new MysqlDataSource();
        if (database.tls() == null) dataSource.setURL(database.jdbcUrl());
        else database.tls().configure(dataSource);
        dataSource.setUser(database.username());
        dataSource.setPassword(database.password());
        return dataSource;
    }

    private static SmokeDatabase selectDatabase() {
        String externalJdbcUrl = System.getenv(JDBC_URL_ENVIRONMENT);
        if (externalJdbcUrl != null) {
            SmokeDatabase database = externalDatabase(
                    externalJdbcUrl,
                    System.getenv(USERNAME_ENVIRONMENT),
                    externalPassword(),
                    Boolean.getBoolean(ALLOW_REMOTE_PROPERTY),
                    Boolean.getBoolean(ALLOW_UNVERIFIED_TLS_PROPERTY),
                    Boolean.getBoolean(ALLOW_PINNED_CA_PROPERTY),
                    System.getenv(TRUST_STORE_ENVIRONMENT),
                    System.getenv(TRUST_STORE_TYPE_ENVIRONMENT),
                    externalTrustStorePassword());
            validateExternalDatabase(database);
            return database;
        }

        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required unless an explicitly approved external packaged-smoke database is configured");
        MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0.44")
                .withDatabaseName("fitness_packaged_smoke")
                .withUsername("fitness")
                .withPassword("fitness-smoke-password");
        mysql.start();
        return new SmokeDatabase(
                mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword(), null, mysql);
    }

    static SmokeDatabase externalDatabase(
            String jdbcUrl, String username, String password, boolean allowRemote) {
        return externalDatabase(
                jdbcUrl, username, password, allowRemote, false, false, null, null, null);
    }

    static SmokeDatabase externalDatabase(
            String jdbcUrl,
            String username,
            String password,
            boolean allowRemote,
            boolean allowPinnedCa,
            String trustStorePath,
            String trustStoreType,
            String trustStorePassword) {
        return externalDatabase(
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

    static SmokeDatabase externalDatabase(
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
            throw new IllegalArgumentException("External packaged-smoke JDBC URL must be configured");
        }
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("External packaged-smoke username must be configured");
        }
        URI uri;
        try {
            if (!jdbcUrl.startsWith("jdbc:")) {
                throw new URISyntaxException("", "missing JDBC prefix");
            }
            uri = new URI(jdbcUrl.substring("jdbc:".length()));
        } catch (URISyntaxException | IllegalArgumentException exception) {
            throw new IllegalArgumentException("External packaged-smoke JDBC URL is invalid");
        }
        if (!"mysql".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("External packaged-smoke database must use a MySQL JDBC URL");
        }
        if (uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    "External packaged-smoke JDBC URL must not include user-info, query, or fragment");
        }
        if (uri.getHost() == null || uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException(
                    "External packaged-smoke JDBC URL must target exactly one host and port");
        }
        boolean loopback = isLoopbackHost(uri.getHost());
        if (!allowRemote && !loopback) {
            throw new IllegalArgumentException("External packaged-smoke database host must be loopback");
        }
        if (!loopback && !isIpLiteral(uri.getHost())) {
            throw new IllegalArgumentException(
                    "External packaged-smoke database remote host must be an IP literal");
        }
        ExternalMysqlDatabaseTarget.requireAllowed(uri, EXTERNAL_DATABASE_NAME_ERROR);
        if (allowUnverifiedTls && allowPinnedCa) {
            throw new IllegalArgumentException(
                    "External packaged-smoke TLS modes are mutually exclusive");
        }
        ExternalMysqlTls.Configuration tls = loopback
                ? null
                : allowUnverifiedTls
                        ? ExternalMysqlTls.encryptedWithoutIdentityVerification(jdbcUrl)
                        : allowPinnedCa
                        ? ExternalMysqlTls.pinnedCa(
                                jdbcUrl, trustStorePath, trustStoreType, trustStorePassword)
                        : ExternalMysqlTls.verifiedIdentity(jdbcUrl);
        return new SmokeDatabase(
                tls == null ? jdbcUrl : tls.jdbcUrl(),
                username,
                password == null ? "" : password,
                tls,
                null);
    }

    private static String externalPassword() {
        return ExternalTestSecret.read(
                System.getenv(PASSWORD_ENVIRONMENT),
                System.getenv(PASSWORD_FILE_ENVIRONMENT),
                "External packaged-smoke MySQL password");
    }

    private static String externalTrustStorePassword() {
        return ExternalTestSecret.read(
                System.getenv(TRUST_STORE_PASSWORD_ENVIRONMENT),
                System.getenv(TRUST_STORE_PASSWORD_FILE_ENVIRONMENT),
                "External packaged-smoke MySQL trust store password");
    }

    private static void validateExternalDatabase(SmokeDatabase database) {
        try (Connection connection = dataSource(database).getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            validateExternalServerVersion(
                    metadata.getDatabaseProductName(),
                    metadata.getDatabaseMajorVersion(),
                    metadata.getDatabaseMinorVersion());
            String expectedDatabase = ExternalMysqlDatabaseTarget.requireAllowed(
                    URI.create(database.jdbcUrl().substring("jdbc:".length())),
                    EXTERNAL_DATABASE_NAME_ERROR);
            if (!expectedDatabase.equals(connection.getCatalog())) {
                throw new IllegalArgumentException(EXTERNAL_DATABASE_NAME_ERROR);
            }
            if (database.tls() != null) assertEncryptedConnection(connection);
            ExternalMysqlValidationMarker.verifyAndConsume(
                    System.getProperty(VALIDATION_MARKER_PROPERTY),
                    System.getProperty(VERIFICATION_RUN_ID_PROPERTY),
                    database.jdbcUrl(),
                    connection);
        } catch (SQLException exception) {
            throw new IllegalArgumentException("Unable to validate external packaged-smoke database");
        }
    }

    static void validateExternalServerVersion(
            String productName,
            int majorVersion,
            int minorVersion) {
        if (!"MySQL".equalsIgnoreCase(productName) || majorVersion != 8) {
            throw new IllegalArgumentException(
                    "External packaged-smoke database server must be MySQL 8");
        }
    }

    private static boolean isLoopbackHost(String host) {
        if (host == null) return false;
        String normalized = host.toLowerCase(Locale.ROOT);
        if ("localhost".equals(normalized)) {
            try {
                return java.util.Arrays.stream(java.net.InetAddress.getAllByName(normalized))
                        .allMatch(java.net.InetAddress::isLoopbackAddress);
            } catch (java.net.UnknownHostException exception) {
                return false;
            }
        }
        if ("::1".equals(normalized) || "[::1]".equals(normalized)) return true;
        String[] octets = normalized.split("\\.", -1);
        if (octets.length != 4 || !"127".equals(octets[0])) return false;
        for (String octet : octets) {
            try {
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) return false;
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

    private static void assertEncryptedConnection(Connection connection) throws SQLException {
        try (var statement = connection.createStatement();
                var status = statement.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")) {
            if (!status.next() || status.getString(2) == null || status.getString(2).isBlank()) {
                throw new IllegalArgumentException(
                        "External packaged-smoke database connection must use TLS");
            }
        }
    }

    static void stopApplication(Process application) throws InterruptedException {
        try {
            application.destroy();
            if (!application.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                application.destroyForcibly();
                if (!application.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    throw new IllegalStateException("packaged application process did not stop");
                }
            }
            assertThat(application.isAlive()).isFalse();
        } catch (InterruptedException exception) {
            application.destroyForcibly();
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            boolean stopped = false;
            do {
                try {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) break;
                    stopped = application.waitFor(
                            remaining, java.util.concurrent.TimeUnit.NANOSECONDS);
                } catch (InterruptedException repeatedInterruption) {
                    // Keep the interrupt pending while completing bounded process cleanup.
                }
            } while (!stopped);
            Thread.currentThread().interrupt();
            if (!stopped || application.isAlive()) {
                throw new IllegalStateException(
                        "packaged application process did not stop after interruption", exception);
            }
            throw exception;
        }
    }

    static void assertStagingProfileLogged(Path log) throws IOException {
        assertThat(Files.readString(log).contains("staging-experience"))
                .as("packaged application must log the staging-experience profile")
                .isTrue();
    }

    static void sanitizeLog(Path log, SmokeDatabase database) throws IOException {
        if (!Files.exists(log)) return;
        String sanitized = Files.readString(log)
                .replace(database.jdbcUrl(), "jdbc:mysql://[redacted]")
                .replaceAll("jdbc:mysql://[^\\s)]+", "jdbc:mysql://[redacted]")
                .replaceAll(
                        "(?i)(Access denied for user\\s+)'[^']*'@'[^']*'",
                        "$1'[redacted]'@'[redacted]'")
                .replaceAll("(?i)(user(?:name)?=)[^\\s,;]+", "$1[redacted]");
        if (!database.username().isBlank()) {
            sanitized = sanitized.replace(database.username(), "[redacted]");
        }
        if (!database.password().isBlank()) {
            sanitized = sanitized.replace(database.password(), "[redacted]");
        }
        if (database.tls() != null && database.tls().usesPinnedCa()) {
            sanitized = sanitized.replace(database.tls().trustStoreUrl(), "[redacted-trust-store]");
            sanitized = sanitized.replace(database.tls().trustStorePassword(), "[redacted]");
        }
        Files.writeString(log, sanitized);
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    static final class SmokeDatabase implements AutoCloseable {
        private final String jdbcUrl;
        private final String username;
        private final String password;
        private final ExternalMysqlTls.Configuration tls;
        private final MySQLContainer<?> managedContainer;

        SmokeDatabase(
                String jdbcUrl,
                String username,
                String password,
                ExternalMysqlTls.Configuration tls,
                MySQLContainer<?> managedContainer) {
            this.jdbcUrl = jdbcUrl;
            this.username = username;
            this.password = password;
            this.tls = tls;
            this.managedContainer = managedContainer;
        }

        String jdbcUrl() {
            return jdbcUrl;
        }

        String username() {
            return username;
        }

        String password() {
            return password;
        }

        ExternalMysqlTls.Configuration tls() {
            return tls;
        }

        @Override
        public void close() {
            if (managedContainer != null) managedContainer.stop();
        }

        @Override
        public String toString() {
            return "SmokeDatabase[credentials=redacted]";
        }
    }
}
