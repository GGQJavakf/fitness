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
            String unit) {
        public Prescription(
                int workSets, int repMin, int repMax, int restSeconds, String weightStatus, String unit) {
            this(workSets, repMin, repMax, restSeconds, weightStatus, Optional.empty(), unit);
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
        }

        /** Retargets an immutable prescription when a replacement changes its load mode. */
        public Prescription forReplacement(
                Set<String> sourceEquipment, Set<String> replacementEquipment) {
            Set<String> source = validEquipment(sourceEquipment, "source equipment");
            Set<String> replacement = validEquipment(replacementEquipment, "replacement equipment");
            if (isBodyweight(replacement)) {
                return new Prescription(
                        workSets, repMin, repMax, restSeconds,
                        "BODYWEIGHT", Optional.empty(), unit);
            }
            if (isBodyweight(source) || !source.equals(replacement)) {
                return new Prescription(
                        workSets, repMin, repMax, restSeconds,
                        "NEEDS_CALIBRATION", Optional.empty(), unit);
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
