package com.aifitness.assistant.plan.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Public plan-module view used to create an immutable workout snapshot. */
public interface PlanWorkoutSnapshotQuery {
    PlanDaySource load(UUID userId, UUID planId, int versionNumber, String trainingDayCode);

    record PlanDaySource(
            UUID planId,
            UUID planVersionId,
            int versionNumber,
            UUID trainingDayId,
            String trainingDayCode,
            List<ExerciseSource> exercises) {
        public PlanDaySource {
            Objects.requireNonNull(planId, "plan id must not be null");
            Objects.requireNonNull(planVersionId, "plan version id must not be null");
            Objects.requireNonNull(trainingDayId, "training day id must not be null");
            if (versionNumber < 1 || trainingDayCode == null || trainingDayCode.isBlank()) {
                throw new IllegalArgumentException("plan snapshot reference is invalid");
            }
            exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
            if (exercises.isEmpty()) {
                throw new IllegalArgumentException("plan snapshot requires exercises");
            }
        }
    }

    record ExerciseSource(
            UUID sourcePlanExerciseId,
            int order,
            String exerciseCode,
            String exerciseName,
            String contentVersion,
            Set<String> equipment,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            String weightStatus,
            java.util.Optional<BigDecimal> targetWeightKg,
            String unit) {
        public ExerciseSource(
                UUID sourcePlanExerciseId,
                int order,
                String exerciseCode,
                String exerciseName,
                String contentVersion,
                Set<String> equipment,
                int workSets,
                int repMin,
                int repMax,
                int restSeconds,
                String weightStatus,
                String unit) {
            this(sourcePlanExerciseId, order, exerciseCode, exerciseName, contentVersion, equipment,
                    workSets, repMin, repMax, restSeconds, weightStatus, java.util.Optional.empty(), unit);
        }

        public ExerciseSource {
            Objects.requireNonNull(sourcePlanExerciseId, "source plan exercise id must not be null");
            equipment = Set.copyOf(Objects.requireNonNull(equipment, "equipment must not be null"));
            targetWeightKg = Objects.requireNonNull(targetWeightKg, "target weight must not be null");
        }
    }

    final class PlanSnapshotNotFoundException extends RuntimeException {}
}
