package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.workout.application.WorkoutSessionStartTransaction;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared JDBC transaction boundary for recovery assessment, confirmation consume, and session create. */
public final class JdbcWorkoutSessionStartTransaction implements WorkoutSessionStartTransaction {
    private final TransactionTemplate transactions;

    public JdbcWorkoutSessionStartTransaction(DataSource dataSource) {
        DataSource required = Objects.requireNonNull(dataSource, "dataSource must not be null");
        this.transactions = new TransactionTemplate(new DataSourceTransactionManager(required));
        this.transactions.setIsolationLevel(TransactionDefinition.ISOLATION_SERIALIZABLE);
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    @Override
    public <T> T execute(Supplier<T> action) {
        Objects.requireNonNull(action, "action must not be null");
        return Objects.requireNonNull(transactions.execute(ignored -> action.get()));
    }
}
