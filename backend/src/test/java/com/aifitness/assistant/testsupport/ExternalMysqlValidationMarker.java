package com.aifitness.assistant.testsupport;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

public final class ExternalMysqlValidationMarker {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final List<String> EXPECTED_VERSIONS = List.of(
            "001", "002", "003", "004", "005", "006", "007", "008", "009", "010", "011", "012",
            "013", "014", "015", "016", "017", "018", "019", "020", "021", "022", "023", "024",
            "025");

    private ExternalMysqlValidationMarker() {}

    public static void clear(String markerPath) {
        Path path = requireMarkerPath(markerPath);
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            throw new IllegalArgumentException("External MySQL validation marker cannot be cleared");
        }
    }

    public static void write(
            String markerPath,
            String runId,
            String jdbcUrl,
            Connection connection) {
        Path path = requireMarkerPath(markerPath);
        Marker marker = inspect(runId, jdbcUrl, connection);
        Path parent = path.getParent();
        if (parent == null) {
            throw new IllegalArgumentException("External MySQL validation marker path is invalid");
        }
        try {
            Files.createDirectories(parent);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && Files.isSymbolicLink(path)) {
                throw new IllegalArgumentException("External MySQL validation marker must not be a symbolic link");
            }
            Path temporary = Files.createTempFile(parent, ".external-mysql-validation-", ".tmp");
            try {
                Files.writeString(temporary, JSON.writeValueAsString(marker), StandardCharsets.UTF_8);
                try {
                    Files.move(
                            temporary,
                            path,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (java.nio.file.AtomicMoveNotSupportedException ignored) {
                    Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(temporary);
            }
        } catch (IOException ignored) {
            throw new IllegalArgumentException("External MySQL validation marker cannot be written");
        }
    }

    public static void verifyAndConsume(
            String markerPath,
            String runId,
            String jdbcUrl,
            Connection connection) {
        Path path = requireMarkerPath(markerPath);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) {
            throw new IllegalArgumentException(
                    "External packaged-smoke database requires this run's migration validation marker");
        }
        try {
            Marker expected = JSON.readValue(Files.readString(path, StandardCharsets.UTF_8), Marker.class);
            Marker actual = inspect(runId, jdbcUrl, connection);
            if (!expected.equals(actual)) {
                throw new IllegalArgumentException(
                        "External packaged-smoke database does not match this run's migration validation");
            }
            Files.delete(path);
        } catch (IOException ignored) {
            throw new IllegalArgumentException("External MySQL validation marker cannot be read");
        }
    }

    private static Marker inspect(String runId, String jdbcUrl, Connection connection) {
        if (runId == null || runId.isBlank()) {
            throw new IllegalArgumentException("External MySQL validation run id must be configured");
        }
        try {
            String catalog = connection.getCatalog();
            String serverUuid;
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery("SELECT @@server_uuid")) {
                if (!result.next() || result.getString(1) == null || result.getString(1).isBlank()) {
                    throw new IllegalArgumentException("External MySQL server identity is unavailable");
                }
                serverUuid = result.getString(1);
            }

            List<String> versions = new ArrayList<>();
            StringBuilder history = new StringBuilder();
            try (var statement = connection.createStatement();
                    var result = statement.executeQuery(
                            "SELECT version, checksum FROM flyway_schema_history "
                                    + "WHERE success = 1 ORDER BY installed_rank")) {
                while (result.next()) {
                    String version = result.getString(1);
                    versions.add(version);
                    history.append(version)
                            .append(':')
                            .append(result.getString(2))
                            .append('\n');
                }
            }
            if (!versions.equals(EXPECTED_VERSIONS)) {
                throw new IllegalArgumentException(
                        "External MySQL migration history does not match " + expectedHistoryRange());
            }
            return new Marker(
                    runId,
                    sha256(jdbcUrl),
                    catalog,
                    serverUuid,
                    sha256(history.toString()),
                    List.copyOf(versions));
        } catch (SQLException ignored) {
            throw new IllegalArgumentException("External MySQL validation evidence cannot be inspected");
        }
    }

    private static Path requireMarkerPath(String markerPath) {
        if (markerPath == null || markerPath.isBlank()) {
            throw new IllegalArgumentException("External MySQL validation marker path must be configured");
        }
        try {
            return Path.of(markerPath).toAbsolutePath().normalize();
        } catch (RuntimeException ignored) {
            throw new IllegalArgumentException("External MySQL validation marker path is invalid");
        }
    }

    static List<String> expectedVersions() {
        return EXPECTED_VERSIONS;
    }

    private static String expectedHistoryRange() {
        return "V" + EXPECTED_VERSIONS.getFirst() + " through V" + EXPECTED_VERSIONS.getLast();
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable");
        }
    }

    private record Marker(
            String runId,
            String jdbcUrlSha256,
            String catalog,
            String serverUuid,
            String flywayHistorySha256,
            List<String> versions) {}
}
