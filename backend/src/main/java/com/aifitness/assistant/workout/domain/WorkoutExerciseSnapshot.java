package com.aifitness.assistant.workout.domain;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record WorkoutExerciseSnapshot(
        UUID id,
        UUID sessionId,
        UUID sourcePlanExerciseId,
        int order,
        String exerciseCode,
        String exerciseName,
        String contentVersion,
        Set<String> equipment,
        Prescription prescription,
        Status status) {

    public WorkoutExerciseSnapshot {
        Objects.requireNonNull(id, "snapshot id must not be null");
        Objects.requireNonNull(sessionId, "session id must not be null");
        Objects.requireNonNull(sourcePlanExerciseId, "source plan exercise id must not be null");
        if (order < 1) {
            throw new IllegalArgumentException("exercise order must be positive");
        }
        exerciseCode = required(exerciseCode, "exercise code");
        exerciseName = required(exerciseName, "exercise name");
        contentVersion = required(contentVersion, "content version");
        equipment = Set.copyOf(Objects.requireNonNull(equipment, "equipment must not be null"));
        Objects.requireNonNull(prescription, "prescription must not be null");
        Objects.requireNonNull(status, "snapshot status must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    public record Prescription(
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            String weightStatus,
            Optional<BigDecimal> targetWeightKg,
            String unit,
            Optional<Integer> targetRirMin,
            Optional<Integer> targetRirMax,
            Optional<Integer> eccentricSeconds,
            boolean perSide,
            Optional<String> executionGroup,
            Optional<Integer> executionOrder,
            Optional<OptionalSetRule> optionalSetRule) {
        public Prescription(
                int workSets, int repMin, int repMax, int restSeconds, String weightStatus, String unit) {
            this(workSets, repMin, repMax, restSeconds, weightStatus, Optional.empty(), unit,
                    Optional.empty(), Optional.empty(), Optional.empty(), false,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        public Prescription(
                int workSets, int repMin, int repMax, int restSeconds, String weightStatus,
                Optional<BigDecimal> targetWeightKg, String unit) {
            this(workSets, repMin, repMax, restSeconds, weightStatus, targetWeightKg, unit,
                    Optional.empty(), Optional.empty(), Optional.empty(), false,
                    Optional.empty(), Optional.empty(), Optional.empty());
        }

        public Prescription {
            if (workSets < 0 || repMin < 0 || repMax < repMin || restSeconds < 0) {
                throw new IllegalArgumentException("snapshot prescription is invalid");
            }
            weightStatus = required(weightStatus, "weight status");
            targetWeightKg = Objects.requireNonNull(targetWeightKg, "target weight must not be null")
                    .map(BigDecimal::stripTrailingZeros);
            if (targetWeightKg.filter(weight -> weight.signum() < 0).isPresent()) {
                throw new IllegalArgumentException("target weight must not be negative");
            }
            if (!"KG".equals(unit)) {
                throw new IllegalArgumentException("P0 workout snapshots only support KG");
            }
            targetRirMin = positiveOptional(targetRirMin, "target RIR minimum", true);
            targetRirMax = positiveOptional(targetRirMax, "target RIR maximum", true);
            eccentricSeconds = positiveOptional(eccentricSeconds, "eccentric seconds", false);
            if (targetRirMin.isPresent() != targetRirMax.isPresent()
                    || targetRirMin.isPresent()
                    && (targetRirMin.orElseThrow() > targetRirMax.orElseThrow()
                    || targetRirMax.orElseThrow() > 10)) {
                throw new IllegalArgumentException("target RIR range is invalid");
            }
            if (eccentricSeconds.filter(value -> value > 10).isPresent()) {
                throw new IllegalArgumentException("eccentric seconds is invalid");
            }
            executionGroup = Objects.requireNonNull(executionGroup, "execution group must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
            executionOrder = positiveOptional(executionOrder, "execution order", false);
            if (executionGroup.isPresent() != executionOrder.isPresent()) {
                throw new IllegalArgumentException("execution group and order must be provided together");
            }
            optionalSetRule = Objects.requireNonNull(optionalSetRule, "optional set rule must not be null");
        }

        /** Retargets an immutable prescription when a replacement changes its load mode. */
        public Prescription forReplacement(
                Set<String> sourceEquipment, Set<String> replacementEquipment) {
            Set<String> source = validEquipment(sourceEquipment, "source equipment");
            Set<String> replacement = validEquipment(replacementEquipment, "replacement equipment");
            if (isBodyweight(replacement)) {
                return new Prescription(
                        workSets, repMin, repMax, restSeconds,
                        "BODYWEIGHT", Optional.empty(), unit,
                        targetRirMin, targetRirMax, eccentricSeconds, perSide,
                        executionGroup, executionOrder, optionalSetRule);
            }
            if (isBodyweight(source) || !source.equals(replacement)) {
                return new Prescription(
                        workSets, repMin, repMax, restSeconds,
                        "NEEDS_CALIBRATION", Optional.empty(), unit,
                        targetRirMin, targetRirMax, eccentricSeconds, perSide,
                        executionGroup, executionOrder, optionalSetRule);
            }
            return this;
        }

        private static Set<String> validEquipment(Set<String> value, String name) {
            Set<String> equipment = Set.copyOf(Objects.requireNonNull(value, name + " must not be null"));
            if (equipment.isEmpty()
                    || (equipment.contains("BODYWEIGHT") && equipment.size() != 1)) {
                throw new IllegalArgumentException(name + " has an invalid load mode");
            }
            return equipment;
        }

        private static boolean isBodyweight(Set<String> equipment) {
            return equipment.size() == 1 && equipment.contains("BODYWEIGHT");
        }

        private static Optional<Integer> positiveOptional(
                Optional<Integer> value, String field, boolean allowZero) {
            Optional<Integer> normalized = Objects.requireNonNull(value, field + " must not be null");
            if (normalized.filter(number -> allowZero ? number < 0 : number <= 0).isPresent()) {
                throw new IllegalArgumentException(field + " is invalid");
            }
            return normalized;
        }

        public record OptionalSetRule(
                String conditionCode, String exclusiveChoiceGroup, int additionalSets,
                Optional<String> description) {
            public OptionalSetRule {
                conditionCode = required(conditionCode, "optional set condition code");
                exclusiveChoiceGroup = required(exclusiveChoiceGroup, "optional set exclusive group");
                if (additionalSets != 1) throw new IllegalArgumentException("optional set amount must be one");
                description = Objects.requireNonNull(description, "optional set description must not be null")
                        .map(String::trim).filter(value -> !value.isEmpty());
            }
        }
    }

    public enum Status {
        PENDING,
        ACTIVE,
        COMPLETED,
        SKIPPED,
        ABORTED,
        REPLACED
    }
}
