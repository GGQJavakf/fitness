package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutSession;
import java.util.Optional;
import java.util.UUID;

public interface WorkoutSessionRepository {
    Optional<WorkoutSession> findByIdAndUser(UUID sessionId, UUID userId);

    Optional<WorkoutSession> findByUserAndClientKey(UUID userId, String clientSessionKey);

    WorkoutSession create(WorkoutSession session);

    WorkoutSession update(WorkoutSession session, long expectedVersion);
}
