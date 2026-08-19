package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Read-only, bounded projection used by the workout history list. */
public interface WorkoutHistoryRepository {
    List<Projection> findHistory(
            UUID userId, Optional<Instant> beforeStartedAt, Optional<UUID> beforeId, int limit);

    record Projection(
            UUID sessionId,
            String trainingDayCode,
            String trainingDayName,
            WorkoutStatus status,
            Instant startedAt,
            Instant completedAt,
            int completedWorkSets,
            BigDecimal completedVolumeKg,
            int completedReps,
            boolean usesExternalLoad) {}
}
