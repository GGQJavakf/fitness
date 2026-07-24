package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import java.util.Optional;
import java.util.UUID;

public interface PlanRepository {
    Optional<TrainingPlan> findActiveByUser(UUID userId);

    Optional<TrainingPlan> findByIdAndUser(UUID planId, UUID userId);

    TrainingPlan create(UUID userId, TrainingPlanVersion firstVersion);

    TrainingPlan append(UUID userId, UUID planId, int expectedVersion, TrainingPlanVersion version);
}
