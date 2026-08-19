package com.aifitness.assistant.workout.domain;

import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Versioned warm-up facts captured when a workout session is created. */
public record WorkoutWarmupPrescription(
        String schemaVersion,
        String ruleVersion,
        GeneralWarmup generalWarmup,
        Optional<RampWarmup> rampWarmup,
        boolean countsTowardTrainingVolume,
        boolean countsTowardProgression) {

    public WorkoutWarmupPrescription {
        schemaVersion = required(schemaVersion, "warmup schema version");
        ruleVersion = required(ruleVersion, "warmup rule version");
        Objects.requireNonNull(generalWarmup, "general warmup must not be null");
        rampWarmup = Objects.requireNonNull(rampWarmup, "ramp warmup must not be null");
        if (countsTowardTrainingVolume || countsTowardProgression) {
            throw new IllegalArgumentException("warmup must not count toward official volume or progression");
        }
    }

    public record GeneralWarmup(int occurrences, int durationSeconds) {
        public GeneralWarmup {
            if (occurrences != 1 || durationSeconds <= 0) {
                throw new IllegalArgumentException("general warmup must occur once with a positive duration");
            }
        }
    }

    public record RampWarmup(
            UUID exerciseId,
            int exerciseOrder,
            RampStatus status,
            Optional<String> equipmentType,
            List<RampSet> sets,
            Optional<String> calibrationCode,
            Optional<String> calibrationMessage) {
        public RampWarmup {
            Objects.requireNonNull(exerciseId, "ramp exercise id must not be null");
            if (exerciseOrder <= 0) {
                throw new IllegalArgumentException("ramp exercise order must be positive");
            }
            Objects.requireNonNull(status, "ramp status must not be null");
            equipmentType = Objects.requireNonNull(equipmentType, "equipment type must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
            sets = List.copyOf(Objects.requireNonNull(sets, "ramp sets must not be null"));
            calibrationCode = Objects.requireNonNull(calibrationCode, "calibration code must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
            calibrationMessage = Objects.requireNonNull(
                    calibrationMessage, "calibration message must not be null")
                    .map(String::trim).filter(value -> !value.isEmpty());
            if (status == RampStatus.READY && sets.isEmpty()) {
                throw new IllegalArgumentException("ready ramp warmup requires sets");
            }
            if (status == RampStatus.CALIBRATION_REQUIRED
                    && (!sets.isEmpty() || calibrationCode.isEmpty() || calibrationMessage.isEmpty())) {
                throw new IllegalArgumentException("calibration ramp warmup requires an explanation and no sets");
            }
        }
    }

    public record RampSet(BigDecimal weightKg, int reps) {
        public RampSet {
            Objects.requireNonNull(weightKg, "ramp weight must not be null");
            if (weightKg.signum() <= 0 || reps <= 0) {
                throw new IllegalArgumentException("ramp weight and repetitions must be positive");
            }
        }
    }

    public enum RampStatus {
        READY,
        CALIBRATION_REQUIRED
    }

    private static String required(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
