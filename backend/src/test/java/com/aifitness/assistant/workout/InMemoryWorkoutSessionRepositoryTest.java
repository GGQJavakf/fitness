package com.aifitness.assistant.workout;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.workout.application.WorkoutSessionService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryWorkoutSessionRepositoryTest {

    @Test
    void rejectsAConcurrentIdempotencyKeyReuseForADifferentPlanSource() {
        var repository = new InMemoryWorkoutSessionRepository();
        repository.create(session(1, "DAY_1"));

        assertThatThrownBy(() -> repository.create(session(2, "DAY_2")))
                .isInstanceOf(WorkoutSessionService.IdempotencyConflictException.class);
    }

    private static WorkoutSession session(long suffix, String dayCode) {
        UUID sessionId = new UUID(0, 100 + suffix);
        WorkoutExerciseSnapshot snapshot = new WorkoutExerciseSnapshot(
                new UUID(0, 200 + suffix), sessionId, new UUID(0, 300 + suffix), 1,
                "DB_SQUAT", "哑铃深蹲", "content-v1", Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
        return new WorkoutSession(
                sessionId, new UUID(0, 1), new UUID(0, 2), new UUID(0, 3), 1,
                new UUID(0, 4 + suffix), dayCode, "same-client-key", WorkoutStatus.CREATED,
                Instant.parse("2026-07-24T08:00:00Z"), Optional.empty(), 0, List.of(snapshot));
    }
}
