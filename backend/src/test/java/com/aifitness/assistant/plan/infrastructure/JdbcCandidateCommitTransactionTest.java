package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.ByteBuffer;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

class JdbcCandidateCommitTransactionTest {
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000101");

    @Test
    void nestedRequiredPlanWriteUsesTheSameConnectionAndFailureRollsBackTheWholeCommit() throws SQLException {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement userLock = mock(PreparedStatement.class);
        ResultSet lockedUser = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.getAutoCommit()).thenReturn(true);
        when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_UNCOMMITTED);
        when(connection.prepareStatement("SELECT id FROM user_account WHERE id = ? FOR UPDATE"))
                .thenReturn(userLock);
        when(userLock.executeQuery()).thenReturn(lockedUser);
        when(lockedUser.next()).thenReturn(true, false);
        when(lockedUser.getBytes(1)).thenReturn(bytes(USER));
        AtomicBoolean warningConsumed = new AtomicBoolean();
        AtomicBoolean receiptClaimed = new AtomicBoolean();
        JdbcCandidateCommitTransaction outer = new JdbcCandidateCommitTransaction(dataSource);
        TransactionTemplate nestedPlanWrite =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));

        assertThatThrownBy(() -> outer.execute(USER, () -> {
            assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(connection);
            warningConsumed.set(true);
            receiptClaimed.set(true);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (status == STATUS_ROLLED_BACK) {
                        warningConsumed.set(false);
                        receiptClaimed.set(false);
                    }
                }
            });
            return nestedPlanWrite.execute(ignored -> {
                assertThat(DataSourceUtils.getConnection(dataSource)).isSameAs(connection);
                throw new IllegalStateException("simulated plan write failure");
            });
        })).isInstanceOf(IllegalStateException.class)
                .hasMessage("simulated plan write failure");

        assertThat(warningConsumed).isFalse();
        assertThat(receiptClaimed).isFalse();
        verify(dataSource, times(1)).getConnection();
        verify(connection).setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        verify(connection).prepareStatement("SELECT id FROM user_account WHERE id = ? FOR UPDATE");
        verify(userLock).setBytes(1, bytes(USER));
        verify(connection).rollback();
        verify(connection, never()).commit();
    }

    private static byte[] bytes(UUID value) {
        return ByteBuffer.allocate(16)
                .putLong(value.getMostSignificantBits())
                .putLong(value.getLeastSignificantBits())
                .array();
    }
}
