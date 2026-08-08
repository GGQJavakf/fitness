package com.aifitness.assistant.rules.domain;

import java.util.Map;
import java.util.Objects;

public record PlanRulePolicy(
        String version,
        PlanLimits planLimits,
        Prescription prescription,
        Rest rest,
        Duration duration,
        Balance balance,
        SessionComposition sessionComposition,
        Map<PlanGenerationEngine.FitnessGoal, GoalPrescription> goalPrescriptions) {

    public PlanRulePolicy {
        if (version == null || version.isBlank()) {
            throw new IllegalArgumentException("rule version is required");
        }
        Objects.requireNonNull(planLimits, "plan limits must not be null");
        Objects.requireNonNull(prescription, "prescription must not be null");
        Objects.requireNonNull(rest, "rest policy must not be null");
        Objects.requireNonNull(duration, "duration policy must not be null");
        Objects.requireNonNull(balance, "balance policy must not be null");
        Objects.requireNonNull(sessionComposition, "session composition policy must not be null");
        goalPrescriptions = Map.copyOf(
                Objects.requireNonNull(goalPrescriptions, "goal prescriptions must not be null"));
        if (!goalPrescriptions.keySet().containsAll(java.util.Set.of(PlanGenerationEngine.FitnessGoal.values()))) {
            throw new IllegalArgumentException("goal prescriptions must cover every fitness goal");
        }
        goalPrescriptions.forEach((goal, goalPrescription) -> {
            if (goalPrescription.workSets() < prescription.minimumWorkSets()
                    || goalPrescription.workSets() > prescription.maximumWorkSets()) {
                throw new IllegalArgumentException(goal + " work sets must stay within prescription bounds");
            }
            if (goalPrescription.repMin() < prescription.minimumReps()
                    || goalPrescription.repMax() > prescription.maximumReps()) {
                throw new IllegalArgumentException(goal + " repetitions must stay within prescription bounds");
            }
            if (goalPrescription.restSeconds() < rest.minimumSeconds()
                    || goalPrescription.restSeconds() > rest.maximumSeconds()) {
                throw new IllegalArgumentException(goal + " rest must stay within rest bounds");
            }
        });
    }

    public PlanRulePolicy(
            String version,
            PlanLimits planLimits,
            Prescription prescription,
            Rest rest,
            Duration duration,
            Balance balance) {
        this(
                version,
                planLimits,
                prescription,
                rest,
                duration,
                balance,
                SessionComposition.defaults(),
                GoalPrescription.defaults());
    }

    public PlanRulePolicy(
            String version,
            PlanLimits planLimits,
            Prescription prescription,
            Rest rest,
            Duration duration,
            Balance balance,
            SessionComposition sessionComposition) {
        this(
                version,
                planLimits,
                prescription,
                rest,
                duration,
                balance,
                sessionComposition,
                GoalPrescription.defaults());
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

    public record SessionComposition(
            int accessoryWorkSets,
            int accessoryRepMin,
            int accessoryRepMax,
            int accessoryRestSeconds) {
        public SessionComposition {
            positive(accessoryWorkSets, "accessory work sets");
            positiveRange(accessoryRepMin, accessoryRepMax, "accessory repetitions");
            positive(accessoryRestSeconds, "accessory rest seconds");
        }

        public static SessionComposition defaults() {
            return new SessionComposition(
                    2,
                    8,
                    12,
                    60);
        }
    }

    public record GoalPrescription(int workSets, int repMin, int repMax, int restSeconds) {
        public GoalPrescription {
            positive(workSets, "goal work sets");
            positiveRange(repMin, repMax, "goal repetitions");
            positive(restSeconds, "goal rest seconds");
        }

        public static Map<PlanGenerationEngine.FitnessGoal, GoalPrescription> defaults() {
            return Map.of(
                    PlanGenerationEngine.FitnessGoal.STRENGTH,
                    new GoalPrescription(3, 5, 8, 120),
                    PlanGenerationEngine.FitnessGoal.HYPERTROPHY,
                    new GoalPrescription(3, 8, 12, 90),
                    PlanGenerationEngine.FitnessGoal.GENERAL_FITNESS,
                    new GoalPrescription(3, 10, 15, 75));
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
