package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.plan.application.CandidateCommitTransaction;
import java.nio.ByteBuffer;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared JDBC transaction for warning consumption, plan activation, and receipt completion. */
final class JdbcCandidateCommitTransaction implements CandidateCommitTransaction {
    private static final String LOCK_USER = "SELECT id FROM user_account WHERE id = ? FOR UPDATE";

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;

    JdbcCandidateCommitTransaction(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.jdbc = new JdbcTemplate(required);
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public <T> T execute(UUID userId, Supplier<T> action) {
        UUID requiredUser = Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(action, "action must not be null");
        return Objects.requireNonNull(transactions.execute(ignored -> {
            if (jdbc.query(LOCK_USER, (row, rowNumber) -> row.getBytes(1), bytes(requiredUser)).isEmpty()) {
                throw new IllegalStateException("authenticated user disappeared during candidate commit");
            }
            return action.get();
        }));
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
