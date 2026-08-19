package com.aifitness.assistant.workout.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcWorkoutSessionStartTransactionTest {
    @Test
    void nestedRequiredSessionTransactionUsesSameConnectionAndCreateFailureRollsBackConsume() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
        AtomicBoolean consumed = new AtomicBoolean();
        JdbcWorkoutSessionStartTransaction outer = new JdbcWorkoutSessionStartTransaction(dataSource);
        TransactionTemplate nestedSessionCreate = new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> outer.execute(() -> {
            assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(connection);
            consumed.set(true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) consumed.set(false);
                }
            });
            return nestedSessionCreate.execute(ignored -> {
                assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(connection);
                throw new IllegalStateException("simulated session create failure");
            });
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated session create failure");

        assertThat(consumed).isFalse();
        verify(dataSource, times(1)).getConnection();
        verify(connection).rollback();
        verify(connection, never()).commit();
    }
}
