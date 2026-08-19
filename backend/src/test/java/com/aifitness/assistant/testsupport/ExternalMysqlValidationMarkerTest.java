package com.aifitness.assistant.testsupport;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExternalMysqlValidationMarkerTest {
    private static final String JDBC_URL =
            "jdbc:mysql://192.0.2.10:3306/fitness_m0?sslMode=VERIFY_CA";

    @TempDir
    Path tempDirectory;

    @Test
    void markerBindsRunTargetServerAndCompleteMigrationHistory() throws Exception {
        Path marker = tempDirectory.resolve("external-mysql-validation-marker.json");
        ExternalMysqlValidationMarker.write(
                marker.toString(), "run-1", JDBC_URL, connection("server-1", 100));

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlValidationMarker.verifyAndConsume(
                        marker.toString(), "run-2", JDBC_URL, connection("server-1", 100)))
                .withMessage(
                        "External packaged-smoke database does not match this run's migration validation");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlValidationMarker.verifyAndConsume(
                        marker.toString(), "run-1", JDBC_URL, connection("server-2", 100)))
                .withMessage(
                        "External packaged-smoke database does not match this run's migration validation");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlValidationMarker.verifyAndConsume(
                        marker.toString(), "run-1", JDBC_URL, connection("server-1", 200)))
                .withMessage(
                        "External packaged-smoke database does not match this run's migration validation");

        ExternalMysqlValidationMarker.verifyAndConsume(
                marker.toString(), "run-1", JDBC_URL, connection("server-1", 100));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlValidationMarker.verifyAndConsume(
                        marker.toString(), "run-1", JDBC_URL, connection("server-1", 100)))
                .withMessage(
                        "External packaged-smoke database requires this run's migration validation marker");
    }

    @Test
    void markerMustExistAndHistoryMustBeExactlyV001ThroughV024() throws Exception {
        Path marker = tempDirectory.resolve("external-mysql-validation-marker.json");
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlValidationMarker.verifyAndConsume(
                        marker.toString(), "run-1", JDBC_URL, connection("server-1", 100)))
                .withMessage(
                        "External packaged-smoke database requires this run's migration validation marker");

        assertThatIllegalArgumentException()
                .isThrownBy(() -> ExternalMysqlValidationMarker.write(
                        marker.toString(), "run-1", JDBC_URL, incompleteHistoryConnection()))
                .withMessage("External MySQL migration history does not match V001 through V024");
    }

    private static Connection connection(String serverUuid, int checksumBase) throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("fitness_m0");
        Statement serverStatement = mock(Statement.class);
        Statement historyStatement = mock(Statement.class);
        ResultSet server = mock(ResultSet.class);
        when(server.next()).thenReturn(true);
        when(server.getString(1)).thenReturn(serverUuid);
        when(serverStatement.executeQuery(anyString())).thenReturn(server);

        ResultSet history = mock(ResultSet.class);
        AtomicInteger row = new AtomicInteger();
        when(history.next()).thenAnswer(ignored -> row.get() < 24 && row.incrementAndGet() <= 24);
        when(history.getString(1)).thenAnswer(ignored -> "%03d".formatted(row.get()));
        when(history.getString(2)).thenAnswer(ignored -> Integer.toString(checksumBase + row.get()));
        when(historyStatement.executeQuery(anyString())).thenReturn(history);
        when(connection.createStatement()).thenReturn(serverStatement, historyStatement);
        return connection;
    }

    private static Connection incompleteHistoryConnection() throws Exception {
        Connection connection = mock(Connection.class);
        when(connection.getCatalog()).thenReturn("fitness_m0");
        Statement serverStatement = mock(Statement.class);
        Statement historyStatement = mock(Statement.class);
        ResultSet server = mock(ResultSet.class);
        when(server.next()).thenReturn(true);
        when(server.getString(1)).thenReturn("server-1");
        when(serverStatement.executeQuery(anyString())).thenReturn(server);
        ResultSet history = mock(ResultSet.class);
        when(history.next()).thenReturn(false);
        when(historyStatement.executeQuery(anyString())).thenReturn(history);
        when(connection.createStatement()).thenReturn(serverStatement, historyStatement);
        return connection;
    }
}
