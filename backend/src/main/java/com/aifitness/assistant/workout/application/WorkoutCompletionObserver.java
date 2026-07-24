package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutSession;
import com.aifitness.assistant.workout.domain.WorkoutSet;
import java.util.List;

/** Post-completion application port. Implementations must be idempotent. */
@FunctionalInterface
public interface WorkoutCompletionObserver {
    void onCompleted(AuthenticatedUserId user, WorkoutSession session, List<WorkoutSet> facts);
}
