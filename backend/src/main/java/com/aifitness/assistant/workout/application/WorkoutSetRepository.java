package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.util.UUID;

public interface WorkoutSetRepository {
    SaveResult save(UUID userId, WorkoutSet candidate, long expectedSessionVersion);

    record SaveResult(WorkoutSet set, long sessionVersion) {}
}
