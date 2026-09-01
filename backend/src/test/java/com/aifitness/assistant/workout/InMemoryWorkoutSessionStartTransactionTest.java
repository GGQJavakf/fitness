package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.workout.application.WorkoutRecoveryConfirmationStore;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutRecoveryConfirmationStore;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionStartTransaction;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryWorkoutSessionStartTransactionTest {
    private static final Instant NOW = Instant.parse("2026-08-29T08:00:00Z");
    private static final UUID USER_ID = new UUID(0, 1);
    private static final UUID PLAN_ID = new UUID(0, 2);

    @Test
    void restoresTheActiveSessionReplacementAndConsumedConfirmationWhenTheCommandFails() {
        InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        InMemoryWorkoutRecoveryConfirmationStore confirmations =
                new InMemoryWorkoutRecoveryConfirmationStore();
        InMemoryWorkoutSessionStartTransaction transaction =
                new InMemoryWorkoutSessionStartTransaction(sessions, confirmations);
        WorkoutSession created = sessions.create(session(new UUID(0, 10), "active-client-key", WorkoutStatus.CREATED));
        WorkoutSession active = sessions.update(created.transitionTo(WorkoutStatus.IN_PROGRESS, NOW), 0);
        WorkoutRecoveryConfirmationStore.Binding binding = new WorkoutRecoveryConfirmationStore.Binding(
                USER_ID, PLAN_ID, 1, "DAY_1", "replacement-client-key", "a".repeat(64));
        String token = confirmations.issue(binding, NOW, NOW.plusSeconds(300));

        assertThatThrownBy(() -> transaction.execute(() -> {
            WorkoutSession terminal = active.transitionTo(WorkoutStatus.COMPLETING, NOW)
                    .transitionTo(WorkoutStatus.ABORTED, NOW);
            sessions.complete(terminal, active.version());
            sessions.create(session(new UUID(0, 20), "replacement-client-key", WorkoutStatus.CREATED));
            assertThat(confirmations.consume(binding, token, NOW)).isTrue();
            throw new IllegalStateException("simulated replacement failure");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(sessions.findByIdAndUser(active.id(), USER_ID).orElseThrow())
                .extracting(WorkoutSession::status, WorkoutSession::version)
                .containsExactly(WorkoutStatus.IN_PROGRESS, 1L);
        assertThat(sessions.findByUserAndClientKey(USER_ID, "replacement-client-key")).isEmpty();
        assertThat(confirmations.consume(binding, token, NOW)).isTrue();
    }

    private static WorkoutSession session(UUID sessionId, String clientKey, WorkoutStatus status) {
        WorkoutExerciseSnapshot exercise = new WorkoutExerciseSnapshot(
                new UUID(sessionId.getMostSignificantBits(), sessionId.getLeastSignificantBits() + 1),
                sessionId, new UUID(0, 30), 1, "ROW", "划船", "content-v1", Set.of("CABLE"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
        return new WorkoutSession(
                sessionId, USER_ID, PLAN_ID, new UUID(0, 3), 1, new UUID(0, 4), "DAY_1",
                clientKey, status, NOW, Optional.empty(), 0, List.of(exercise));
    }
}
