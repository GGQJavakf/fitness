package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.workout.application.WorkoutRecoveryFactQuery;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSetRepository;
import com.aifitness.assistant.workout.infrastructure.RepositoryWorkoutRecoveryFactQuery;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RepositoryWorkoutRecoveryFactQueryTest {
    private static final Instant NOW = Instant.parse("2026-08-11T08:00:00Z");
    private static final UUID USER_ID = new UUID(0, 1);
    private static final UUID OTHER_USER_ID = new UUID(0, 2);

    @Test
    void returnsOnlyCompletedEffectiveWorkFactsOwnedByTheRequestedUser() {
        InMemoryWorkoutSessionRepository sessions = new InMemoryWorkoutSessionRepository();
        InMemoryWorkoutSetRepository sets = new InMemoryWorkoutSetRepository(sessions);
        WorkoutSession owned = activeSession(USER_ID, new UUID(0, 10), List.of(
                snapshot(new UUID(0, 11), new UUID(0, 10), 1, "DUMBBELL_BENCH_PRESS"),
                snapshot(new UUID(0, 12), new UUID(0, 10), 2, "INCLINE_PUSH_UP"),
                snapshot(new UUID(0, 13), new UUID(0, 10), 3, "GOBLET_SQUAT")));
        sessions.create(owned);
        sets.save(USER_ID, completedSet(new UUID(0, 21), owned.id(), owned.exercises().get(0).id(),
                "set-key-effective-0001", 1), 0);
        sets.save(USER_ID, completedSet(new UUID(0, 22), owned.id(), owned.exercises().get(1).id(),
                "set-key-voided-000001", 2), 1);
        sets.appendVoid(
                USER_ID, owned.id(), new UUID(0, 22), "void-key-00000001", "b".repeat(64),
                2, new UUID(0, 23), NOW.minusSeconds(19 * 3600L));
        sets.save(USER_ID, completedSet(new UUID(0, 24), owned.id(), owned.exercises().get(2).id(),
                "set-key-extra-0000001", 3, WorkoutSet.SetType.EXTRA), 3);
        complete(sessions, USER_ID, owned.id(), NOW.minusSeconds(18 * 3600L));

        WorkoutSession other = activeSession(OTHER_USER_ID, new UUID(0, 30), List.of(
                snapshot(new UUID(0, 31), new UUID(0, 30), 1, "GOBLET_SQUAT")));
        sessions.create(other);
        sets.save(OTHER_USER_ID, completedSet(new UUID(0, 32), other.id(), other.exercises().get(0).id(),
                "set-key-other-0000001", 1), 0);
        complete(sessions, OTHER_USER_ID, other.id(), NOW.minusSeconds(2 * 3600L));

        WorkoutRecoveryFactQuery query = new RepositoryWorkoutRecoveryFactQuery(sessions, sets);

        assertThat(query.findCompletedExerciseFacts(USER_ID, NOW.minusSeconds(48 * 3600L)))
                .containsExactly(new WorkoutRecoveryFactQuery.CompletedExerciseFact(
                        owned.id(), NOW.minusSeconds(18 * 3600L), "DUMBBELL_BENCH_PRESS", "content-v1"));
    }

    private static WorkoutSession activeSession(
            UUID userId, UUID sessionId, List<WorkoutExerciseSnapshot> exercises) {
        return new WorkoutSession(
                sessionId, userId, new UUID(0, 40), new UUID(0, 41), 1, new UUID(0, 42),
                "DAY_1", "session-key-" + sessionId, WorkoutStatus.IN_PROGRESS,
                NOW.minusSeconds(20 * 3600L), Optional.empty(), 0, exercises);
    }

    private static WorkoutExerciseSnapshot snapshot(
            UUID id, UUID sessionId, int order, String exerciseCode) {
        return new WorkoutExerciseSnapshot(
                id, sessionId, new UUID(0, 50 + order), order, exerciseCode, "训练动作", "content-v1",
                Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
    }

    private static WorkoutSet completedSet(
            UUID id, UUID sessionId, UUID exerciseId, String clientKey, long sequence) {
        return completedSet(id, sessionId, exerciseId, clientKey, sequence, WorkoutSet.SetType.WORK);
    }

    private static WorkoutSet completedSet(
            UUID id, UUID sessionId, UUID exerciseId, String clientKey, long sequence,
            WorkoutSet.SetType setType) {
        WorkoutSet.Performance performance = new WorkoutSet.Performance(new BigDecimal("20"), "KG", 10);
        return new WorkoutSet(
                id, sessionId, exerciseId, clientKey, sequence, setType, 1,
                performance, performance, 2, WorkoutSet.CompletionStatus.COMPLETED,
                Optional.of(NOW.minusSeconds(19 * 3600L)), 0, Optional.empty(), "a".repeat(64));
    }

    private static void complete(
            InMemoryWorkoutSessionRepository sessions, UUID userId, UUID sessionId, Instant completedAt) {
        WorkoutSession active = sessions.findByIdAndUser(sessionId, userId).orElseThrow();
        WorkoutSession terminal = active.transitionTo(WorkoutStatus.COMPLETING, completedAt)
                .transitionTo(WorkoutStatus.COMPLETED, completedAt);
        sessions.complete(terminal, active.version());
    }
}
