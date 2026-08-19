package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.workout.domain.WorkoutRecoveryAssessment;
import java.util.UUID;

@FunctionalInterface
public interface WorkoutRecoveryAssessmentQuery {
    WorkoutRecoveryAssessment check(
            AuthenticatedUserId user, UUID planId, int planVersionNumber, String trainingDayCode);
}
