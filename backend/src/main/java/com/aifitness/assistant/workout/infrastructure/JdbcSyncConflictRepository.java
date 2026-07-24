package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.SyncConflictRepository;
import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.SyncConflict;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.ByteBuffer;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class JdbcSyncConflictRepository implements SyncConflictRepository {
    private static final TypeReference<Map<String, String>> EVIDENCE = new TypeReference<>() {};
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final ObjectMapper json;
    private final Clock clock;

    public JdbcSyncConflictRepository(DataSource dataSource, ObjectMapper json, Clock clock) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.json = Objects.requireNonNull(json, "json must not be null");
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
    }

    @Override
    public SyncConflict save(SyncConflict conflict) {
        jdbc.update("""
                INSERT INTO sync_conflict
                    (id, user_id, entity_type, entity_key, local_payload_json, server_payload_json,
                     status, resolution, sync_version, resolved_at, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bytes(conflict.id()), bytes(conflict.userId()), conflict.entityType(), conflict.entityKey(),
                write(conflict.localEvidence()), write(conflict.serverEvidence()), conflict.status().name(),
                conflict.resolution().map(Enum::name).orElse(null), conflict.version(),
                conflict.resolvedAt().map(Timestamp::from).orElse(null), Timestamp.from(conflict.createdAt()));
        return conflict;
    }

    @Override
    public List<SyncConflict> listOpen(UUID userId) {
        return jdbc.query("""
                SELECT * FROM sync_conflict
                WHERE user_id = ? AND status = 'OPEN'
                ORDER BY created_at, id
                """, (row, ignored) -> read(row), bytes(userId));
    }

    @Override
    public SyncConflict resolve(
            UUID userId, UUID conflictId, SyncConflict.Resolution resolution, long expectedVersion) {
        return Objects.requireNonNull(transactions.execute(ignored -> {
            List<SyncConflict> found = jdbc.query("""
                    SELECT * FROM sync_conflict WHERE id = ? AND user_id = ? FOR UPDATE
                    """, (row, index) -> read(row), bytes(conflictId), bytes(userId));
            if (found.isEmpty()) throw new WorkoutSessionService.SessionNotFoundException();
            SyncConflict current = found.getFirst();
            if (current.status() != SyncConflict.Status.OPEN || current.version() != expectedVersion) {
                throw new WorkoutSessionService.VersionConflictException(current.version());
            }
            SyncConflict resolved = current.resolve(resolution, expectedVersion, clock.instant());
            int changed = jdbc.update("""
                    UPDATE sync_conflict
                    SET status = 'RESOLVED', resolution = ?, sync_version = ?, resolved_at = ?
                    WHERE id = ? AND user_id = ? AND status = 'OPEN' AND sync_version = ?
                    """, resolution.name(), resolved.version(), Timestamp.from(resolved.resolvedAt().orElseThrow()),
                    bytes(conflictId), bytes(userId), expectedVersion);
            if (changed != 1) throw new WorkoutSessionService.VersionConflictException(current.version());
            return resolved;
        }));
    }

    private SyncConflict read(ResultSet row) throws SQLException {
        String resolution = row.getString("resolution");
        Timestamp resolvedAt = row.getTimestamp("resolved_at");
        return new SyncConflict(
                uuid(row.getBytes("id")), uuid(row.getBytes("user_id")), row.getString("entity_type"),
                row.getString("entity_key"), read(row.getString("local_payload_json")),
                read(row.getString("server_payload_json")), SyncConflict.Status.valueOf(row.getString("status")),
                resolution == null ? Optional.empty() : Optional.of(SyncConflict.Resolution.valueOf(resolution)),
                row.getLong("sync_version"), row.getTimestamp("created_at").toInstant(),
                Optional.ofNullable(resolvedAt).map(Timestamp::toInstant));
    }

    private String write(Map<String, String> value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException exception) { throw new IllegalArgumentException("sync evidence is invalid", exception); }
    }

    private Map<String, String> read(String value) {
        try { return json.readValue(value, EVIDENCE); }
        catch (JsonProcessingException exception) { throw new IllegalStateException("stored sync evidence is invalid", exception); }
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16).putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer buffer = ByteBuffer.wrap(value);
        return new UUID(buffer.getLong(), buffer.getLong());
    }
}
