package com.aifitness.assistant.rules.domain;

import java.util.Objects;

public record PlanRulePolicy(
        String version,
        PlanLimits planLimits,
        Prescription prescription,
        Rest rest,
        Duration duration,
        Balance balance) {

    public PlanRulePolicy {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("rule version is required");
        }
        Objects.requireNonNull(planLimits, "plan limits must not be null");
        Objects.requireNonNull(prescription, "prescription must not be null");
        Objects.requireNonNull(rest, "rest policy must not be null");
        Objects.requireNonNull(duration, "duration policy must not be null");
        Objects.requireNonNull(balance, "balance policy must not be null");
    }

    public record PlanLimits(
            int minimumSessionsPerWeek,
            int maximumSessionsPerWeek,
            int maximumExercisesPerSession,
            int maximumEstimatedMinutes) {
        public PlanLimits {
            positiveRange(minimumSessionsPerWeek, maximumSessionsPerWeek, "session frequency");
            positive(maximumExercisesPerSession, "maximum exercises");
            positive(maximumEstimatedMinutes, "maximum estimated minutes");
        }
    }

    public record Prescription(int minimumWorkSets, int maximumWorkSets, int minimumReps, int maximumReps) {
        public Prescription {
            positiveRange(minimumWorkSets, maximumWorkSets, "work sets");
            positiveRange(minimumReps, maximumReps, "repetitions");
        }
    }

    public record Rest(int minimumSeconds, int maximumSeconds) {
        public Rest {
            positiveRange(minimumSeconds, maximumSeconds, "rest seconds");
        }
    }

    public record Duration(int secondsPerWorkSet, int secondsPerExerciseTransition) {
        public Duration {
            positive(secondsPerWorkSet, "seconds per work set");
            positive(secondsPerExerciseTransition, "seconds per exercise transition");
        }
    }

    public record Balance(
            int maximumMovementPatternOccurrencesPerSession,
            int maximumWorkSetsPerPrimaryMusclePerSession,
            int minimumRecoveryHoursBetweenPrimaryMuscleSessions) {
        public Balance {
            positive(maximumMovementPatternOccurrencesPerSession, "maximum movement pattern occurrences");
            positive(maximumWorkSetsPerPrimaryMusclePerSession, "maximum primary muscle work sets");
            positive(minimumRecoveryHoursBetweenPrimaryMuscleSessions, "minimum recovery hours");
        }
    }

    private static void positiveRange(int minimum, int maximum, String field) {
        positive(minimum, "minimum " + field);
        if (maximum < minimum) {
            throw new IllegalArgumentException(field + " range is invalid");
        }
    }

    private static void positive(int value, String field) {
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be positive");
        }
    }
}
