package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

public interface WorkoutSetRepository {
    SaveResult save(UUID userId, WorkoutSet candidate, long expectedSessionVersion);

    Optional<WorkoutSet> find(UUID userId, UUID sessionId, UUID sessionExerciseId, String clientSetKey);

    List<WorkoutSet> findBySession(UUID userId, UUID sessionId);

    record SaveResult(WorkoutSet set, long sessionVersion, boolean duplicate) {}
}
