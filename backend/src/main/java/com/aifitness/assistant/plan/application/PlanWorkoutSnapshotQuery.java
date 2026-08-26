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
            List<WarmupStepSource> warmup,
            List<ExerciseSource> exercises) {
        public PlanDaySource(
                UUID planId, UUID planVersionId, int versionNumber, UUID trainingDayId,
                String trainingDayCode, List<ExerciseSource> exercises) {
            this(planId, planVersionId, versionNumber, trainingDayId, trainingDayCode, List.of(), exercises);
        }

        public PlanDaySource {
            Objects.requireNonNull(planId, "plan id must not be null");
            Objects.requireNonNull(planVersionId, "plan version id must not be null");
            Objects.requireNonNull(trainingDayId, "training day id must not be null");
            if (versionNumber < 1 || trainingDayCode == null || trainingDayCode.isBlank()) {
                throw new IllegalArgumentException("plan snapshot reference is invalid");
            }
            exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
            warmup = List.copyOf(Objects.requireNonNull(warmup, "warmup must not be null"));
            if (exercises.isEmpty()) {
                throw new IllegalArgumentException("plan snapshot requires exercises");
            }
        }
    }

    record WarmupStepSource(String instruction, java.util.Optional<String> prescription, boolean optional) {
        public WarmupStepSource {
            if (instruction == null || instruction.isBlank()) {
                throw new IllegalArgumentException("warmup instruction must not be blank");
            }
            prescription = Objects.requireNonNull(prescription, "warmup prescription must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
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
            String unit,
            java.util.Optional<Integer> targetRirMin,
            java.util.Optional<Integer> targetRirMax,
            java.util.Optional<Integer> eccentricSeconds,
            boolean perSide,
            java.util.Optional<String> executionGroup,
            java.util.Optional<Integer> executionOrder,
            java.util.Optional<OptionalSetRuleSource> optionalSetRule) {
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
                    workSets, repMin, repMax, restSeconds, weightStatus, java.util.Optional.empty(), unit,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        }

        public ExerciseSource(
                UUID sourcePlanExerciseId, int order, String exerciseCode, String exerciseName,
                String contentVersion, Set<String> equipment, int workSets, int repMin, int repMax,
                int restSeconds, String weightStatus, java.util.Optional<BigDecimal> targetWeightKg,
                String unit) {
            this(sourcePlanExerciseId, order, exerciseCode, exerciseName, contentVersion, equipment,
                    workSets, repMin, repMax, restSeconds, weightStatus, targetWeightKg, unit,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty(), false,
                    java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty());
        }

        public ExerciseSource {
            Objects.requireNonNull(sourcePlanExerciseId, "source plan exercise id must not be null");
            equipment = Set.copyOf(Objects.requireNonNull(equipment, "equipment must not be null"));
            targetWeightKg = Objects.requireNonNull(targetWeightKg, "target weight must not be null");
            targetRirMin = Objects.requireNonNull(targetRirMin, "target RIR minimum must not be null");
            targetRirMax = Objects.requireNonNull(targetRirMax, "target RIR maximum must not be null");
            eccentricSeconds = Objects.requireNonNull(eccentricSeconds, "eccentric seconds must not be null");
            executionGroup = Objects.requireNonNull(executionGroup, "execution group must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
            executionOrder = Objects.requireNonNull(executionOrder, "execution order must not be null");
            optionalSetRule = Objects.requireNonNull(optionalSetRule, "optional set rule must not be null");
        }
    }

    record OptionalSetRuleSource(
            String conditionCode, String exclusiveChoiceGroup, int additionalSets,
            java.util.Optional<String> description) {
        public OptionalSetRuleSource {
            if (conditionCode == null || conditionCode.isBlank()
                    || exclusiveChoiceGroup == null || exclusiveChoiceGroup.isBlank()
                    || additionalSets != 1) {
                throw new IllegalArgumentException("optional set rule is invalid");
            }
            description = Objects.requireNonNull(description, "optional set description must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
        }
    }

    final class PlanSnapshotNotFoundException extends RuntimeException {}
}
