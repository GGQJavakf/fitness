package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.workout.infrastructure.JdbcWorkoutHistoryRepository;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class JdbcWorkoutHistoryRepositoryTest {
    @Test
    void pageSizeDoesNotChangeTheSingleSqlRoundTripBudget() throws Exception {
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        ResultSet rows = mock(ResultSet.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);
        JdbcWorkoutHistoryRepository repository = new JdbcWorkoutHistoryRepository(dataSource);
        UUID userId = new UUID(0, 1);

        assertThat(repository.findHistory(userId, Optional.empty(), Optional.empty(), 1)).isEmpty();
        verify(dataSource, times(1)).getConnection();
        ArgumentCaptor<String> firstSql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(firstSql.capture());
        assertThat(firstSql.getValue()).contains(
                "WITH history_page AS", "ranked_sets AS", "metrics AS",
                "NOT EXISTS", "workout_set_void", "training_day_name", "JOIN training_day");

        clearInvocations(dataSource, connection, statement, rows);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(rows);
        when(rows.next()).thenReturn(false);

        assertThat(repository.findHistory(userId, Optional.empty(), Optional.empty(), 50)).isEmpty();
        verify(dataSource, times(1)).getConnection();
        ArgumentCaptor<String> secondSql = ArgumentCaptor.forClass(String.class);
        verify(connection).prepareStatement(secondSql.capture());
        assertThat(secondSql.getValue()).isEqualTo(firstSql.getValue());
    }
}
