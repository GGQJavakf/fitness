package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRepository;
import com.aifitness.assistant.privacy.domain.DeletionRequest;
import java.nio.ByteBuffer;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** MySQL adapter for restart-safe deletion request state. */
public final class JdbcPrivacyRepository implements PrivacyRepository {

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public JdbcPrivacyRepository(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
    }

    @Override
    public Optional<DeletionRequest> findById(UUID id) {
        return query("WHERE id = ?", bytes(id)).stream().findFirst();
    }

    @Override
    public Optional<DeletionRequest> findActiveByUser(UUID userId) {
        return query("WHERE active_user_id = ?", bytes(userId)).stream().findFirst();
    }

    @Override
    public DeletionRequest save(DeletionRequest request) {
        Objects.requireNonNull(request, "request must not be null");
        return Objects.requireNonNull(transactions.execute(ignored -> {
            List<DeletionRequest> existing = query("WHERE id = ? FOR UPDATE", bytes(request.id()));
            if (existing.isEmpty()) {
                jdbc.update("""
                        INSERT INTO privacy_deletion_request
                            (id, user_id, status, requested_at, updated_at, completed_at)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """, bytes(request.id()), bytes(request.userId()), request.status().name(),
                        Timestamp.from(request.requestedAt()), Timestamp.from(request.updatedAt()),
                        completedAt(request));
            } else {
                DeletionRequest persisted = existing.getFirst();
                if (!persisted.userId().equals(request.userId())) {
                    throw new IllegalStateException("deletion request ownership is immutable");
                }
                jdbc.update("""
                        UPDATE privacy_deletion_request
                        SET status = ?, updated_at = ?, completed_at = ?
                        WHERE id = ? AND user_id = ?
                        """, request.status().name(), Timestamp.from(request.updatedAt()),
                        completedAt(request), bytes(request.id()), bytes(request.userId()));
            }
            return findById(request.id()).orElseThrow();
        }));
    }

    private static Timestamp completedAt(DeletionRequest request) {
        return request.status() == DeletionRequest.Status.COMPLETED
                ? Timestamp.from(request.updatedAt()) : null;
    }

    private List<DeletionRequest> query(String clause, Object argument) {
        return jdbc.query("""
                SELECT id, user_id, status, requested_at, updated_at
                FROM privacy_deletion_request
                """ + clause, (row, ignored) -> new DeletionRequest(
                        uuid(row.getBytes(1)), uuid(row.getBytes(2)),
                        DeletionRequest.Status.valueOf(row.getString(3)),
                        row.getTimestamp(4).toInstant(), row.getTimestamp(5).toInstant()), argument);
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits()).putLong(value.getLeastSignificantBits()).array();
    }

    private static UUID uuid(byte[] value) {
        ByteBuffer bytes = ByteBuffer.wrap(value);
        return new UUID(bytes.getLong(), bytes.getLong());
    }
}
