package com.aifitness.assistant.workout;

import com.aifitness.assistant.workout.application.WorkoutSetService;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutStatus;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSessionRepository;
import com.aifitness.assistant.workout.infrastructure.InMemoryWorkoutSetRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

final class WorkoutSetTestFixture {
    static final UUID USER_ID = new UUID(0, 1);
    static final UUID SESSION_ID = new UUID(0, 2);
    static final UUID EXERCISE_ID = new UUID(0, 3);

    private WorkoutSetTestFixture() {}

    static Fixture fixture() {
        var sessions = new InMemoryWorkoutSessionRepository();
        sessions.create(activeSession());
        var sets = new InMemoryWorkoutSetRepository(sessions);
        var service = new WorkoutSetService(
                sets, WorkoutSetService.InputPolicy.conservativeDefaults(),
                Clock.fixed(Instant.parse("2026-07-24T08:00:00Z"), ZoneOffset.UTC),
                () -> new UUID(0, 100));
        return new Fixture(service, sets);
    }

    private static WorkoutSession activeSession() {
        WorkoutExerciseSnapshot snapshot = new WorkoutExerciseSnapshot(
                EXERCISE_ID, SESSION_ID, new UUID(0, 4), 1, "DB_SQUAT", "哑铃深蹲",
                "content-v1", Set.of("DUMBBELL"),
                new WorkoutExerciseSnapshot.Prescription(3, 8, 12, 90, "KNOWN", "KG"),
                WorkoutExerciseSnapshot.Status.PENDING);
        return new WorkoutSession(
                SESSION_ID, USER_ID, new UUID(0, 5), new UUID(0, 6), 1, new UUID(0, 7),
                "DAY_1", "session-key-0001", WorkoutStatus.IN_PROGRESS,
                Instant.parse("2026-07-24T07:55:00Z"), Optional.empty(), 1, List.of(snapshot));
    }

    record Fixture(WorkoutSetService service, InMemoryWorkoutSetRepository repository) {}
}
