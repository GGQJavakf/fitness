package com.aifitness.assistant.workout.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.workout.application.WorkoutRecoveryConfirmationStore;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

class JdbcWorkoutRecoveryConfirmationStoreTest {
    @Test
    void persistsOnlyDigestsAndConsumesWithEveryBoundStartFieldInOneConditionalUpdate() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("INSERT INTO workout_recovery_confirmation"), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.update(contains("SET consumed_at"), any(Object[].class)))
                .thenReturn(1, 0);
        var store = new JdbcWorkoutRecoveryConfirmationStore(
                jdbc, new SecureRandom(new byte[] {1, 2, 3, 4}));
        WorkoutRecoveryConfirmationStore.Binding binding = new WorkoutRecoveryConfirmationStore.Binding(
                UUID.randomUUID(), UUID.randomUUID(), 3, "DAY_A", "client-session-key",
                "a".repeat(64));
        Instant now = Instant.parse("2026-08-11T09:00:00Z");

        String token = store.issue(binding, now, now.plusSeconds(300));

        ArgumentCaptor<Object[]> insertArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO workout_recovery_confirmation"), insertArguments.capture());
        assertThat(insertArguments.getValue()).noneMatch(token::equals);
        assertThat(insertArguments.getValue()).noneMatch(binding.assessmentFingerprint()::equals);
        assertThat(Arrays.stream(insertArguments.getValue())
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast)
                .filter(value -> value.length == 32))
                .hasSize(2);

        assertThat(store.consume(binding, token, now)).isTrue();
        assertThat(store.consume(binding, token, now)).isFalse();
    }
}
