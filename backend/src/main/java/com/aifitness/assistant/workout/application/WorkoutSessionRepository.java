package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutExerciseSnapshot;
import java.util.Optional;
import java.util.List;
import java.time.Instant;
import java.util.UUID;

public interface WorkoutSessionRepository {
    Optional<WorkoutSession> findByIdAndUser(UUID sessionId, UUID userId);

    Optional<WorkoutSession> findByUserAndClientKey(UUID userId, String clientSessionKey);

    /** Locks one user's start scope and reads exact replay plus any active session from the same snapshot. */
    StartState findStartStateForUpdate(UUID userId, String clientSessionKey);

    WorkoutSession create(WorkoutSession session);

    WorkoutSession update(WorkoutSession session, long expectedVersion);

    /** Persists the validated COMPLETING-to-terminal transition as one atomic compare-and-set. */
    WorkoutSession complete(WorkoutSession terminalSession, long expectedVersion);

    WorkoutSession replaceExercise(
            UUID userId, UUID sessionId, UUID snapshotId, long expectedVersion,
            WorkoutExerciseSnapshot replacement);

    List<WorkoutSession> findHistory(
            UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit);

    record StartState(Optional<WorkoutSession> exactReplay, Optional<WorkoutSession> active) {
        public StartState {
            exactReplay = java.util.Objects.requireNonNull(exactReplay, "exact replay must not be null");
            active = java.util.Objects.requireNonNull(active, "active session must not be null");
        }
    }
}
