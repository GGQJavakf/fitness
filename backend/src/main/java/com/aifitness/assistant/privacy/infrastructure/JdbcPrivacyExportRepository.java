package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyDataPort;
import com.aifitness.assistant.privacy.application.PrivacyExportRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;

/** Stores the short-lived export payload as an immutable owner-bound artifact. */
public final class JdbcPrivacyExportRepository implements PrivacyExportRepository {

    private static final TypeReference<Payload> PAYLOAD_TYPE = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcPrivacyExportRepository(DataSource dataSource, ObjectMapper json, Clock clock) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(dataSource, "dataSource must not be null"));
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public ExportArtifact save(ExportArtifact artifact) {
        Objects.requireNonNull(artifact, "artifact must not be null");
        Payload payload = new Payload(
                artifact.resources(), artifact.scope(), artifact.excludedRetentionCategories());
        jdbc.update("""
                INSERT INTO privacy_export_artifact
                    (id, user_id, status, generated_at, expires_at, payload_json)
                VALUES (?, ?, ?, ?, ?, ?)
                """, bytes(artifact.id()), bytes(artifact.userId()), artifact.status(),
                Timestamp.from(artifact.generatedAt()), Timestamp.from(artifact.expiresAt()),
                writeJson(payload));
        removeExpired();
        return artifact;
    }

    @Override
    public Optional<ExportArtifact> findById(UUID id) {
        removeExpired();
        List<ExportArtifact> artifacts = jdbc.query("""
                SELECT user_id, status, generated_at, expires_at, payload_json
                FROM privacy_export_artifact
                WHERE id = ?
                """, (row, ignored) -> {
                    Payload payload = readJson(row.getString(5));
                    return new ExportArtifact(
                            id, uuid(row.getBytes(1)), row.getString(2),
                            row.getTimestamp(3).toInstant(), row.getTimestamp(4).toInstant(),
                            payload.resources(), payload.scope(), payload.excludedRetentionCategories());
                }, bytes(id));
        return artifacts.stream().findFirst();
    }

    private void removeExpired() {
        jdbc.update("DELETE FROM privacy_export_artifact WHERE expires_at <= ?",
                Timestamp.from(clock.instant()));
    }

    private String writeJson(Payload payload) {
        try {
            return json.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("privacy export payload cannot be serialized", exception);
        }
    }

    private Payload readJson(String value) {
        try {
            return json.readValue(value, PAYLOAD_TYPE);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("privacy export payload cannot be read", exception);
        }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }

    private record Payload(
            List<PrivacyDataPort.ResourceExport> resources,
            List<String> scope,
            List<String> excludedRetentionCategories) {
        private Payload {
            resources = List.copyOf(resources);
            scope = List.copyOf(scope);
            excludedRetentionCategories = List.copyOf(excludedRetentionCategories);
        }
    }
}
