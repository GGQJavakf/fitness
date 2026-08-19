package com.aifitness.assistant.rules.domain;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.math.BigDecimal;

public record PlanRulePolicy(
        String version,
        PlanLimits planLimits,
        Prescription prescription,
        Rest rest,
        Duration duration,
        Balance balance,
        SessionComposition sessionComposition,
        Map<PlanGenerationEngine.FitnessGoal, GoalPrescription> goalPrescriptions,
        Warmup warmup) {

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
        Objects.requireNonNull(warmup, "warmup policy must not be null");
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
                GoalPrescription.defaults(),
                Warmup.defaults());
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
                GoalPrescription.defaults(),
                Warmup.defaults());
    }

    public PlanRulePolicy(
            String version,
            PlanLimits planLimits,
            Prescription prescription,
            Rest rest,
            Duration duration,
            Balance balance,
            SessionComposition sessionComposition,
            Map<PlanGenerationEngine.FitnessGoal, GoalPrescription> goalPrescriptions) {
        this(
                version,
                planLimits,
                prescription,
                rest,
                duration,
                balance,
                sessionComposition,
                goalPrescriptions,
                Warmup.defaults());
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

    public record Duration(
            int secondsPerWorkSet,
            int secondsPerWarmupSet,
            int secondsPerExerciseTransition,
            int generalWarmupSeconds,
            int rampWarmupSetsPerSession) {
        public Duration {
            positive(secondsPerWorkSet, "seconds per work set");
            positive(secondsPerWarmupSet, "seconds per warmup set");
            positive(secondsPerExerciseTransition, "seconds per exercise transition");
            positive(generalWarmupSeconds, "general warmup seconds");
            positive(rampWarmupSetsPerSession, "ramp warmup sets per session");
        }

        public Duration(int secondsPerWorkSet, int secondsPerExerciseTransition) {
            this(secondsPerWorkSet, 30, secondsPerExerciseTransition, 180, 2);
        }

        public int sessionWarmupSeconds() {
            return sessionWarmupSeconds(true);
        }

        public int sessionWarmupSeconds(boolean loadedExercisePresent) {
            return generalWarmupSeconds
                    + (loadedExercisePresent ? rampWarmupSetsPerSession * secondsPerWarmupSet : 0);
        }
    }

    public record Balance(
            int maximumMovementPatternOccurrencesPerSession,
            int maximumWorkSetsPerPrimaryMusclePerSession,
            int minimumRecoveryHoursBetweenPrimaryMuscleSessions,
            List<WeeklyMovementPatternTargetSet> weeklyMovementPatternTargets) {
        public Balance {
            positive(maximumMovementPatternOccurrencesPerSession, "maximum movement pattern occurrences");
            positive(maximumWorkSetsPerPrimaryMusclePerSession, "maximum primary muscle work sets");
            positive(minimumRecoveryHoursBetweenPrimaryMuscleSessions, "minimum recovery hours");
            weeklyMovementPatternTargets = List.copyOf(Objects.requireNonNull(
                    weeklyMovementPatternTargets, "weekly movement pattern targets must not be null"));
            Set<Integer> sessionFrequencies = new HashSet<>();
            weeklyMovementPatternTargets.forEach(targetSet -> {
                Objects.requireNonNull(targetSet, "weekly movement pattern target set must not be null");
                if (!sessionFrequencies.add(targetSet.sessionsPerWeek())) {
                    throw new IllegalArgumentException("weekly movement pattern target frequencies must be unique");
                }
            });
        }

        public Balance(
                int maximumMovementPatternOccurrencesPerSession,
                int maximumWorkSetsPerPrimaryMusclePerSession,
                int minimumRecoveryHoursBetweenPrimaryMuscleSessions) {
            this(
                    maximumMovementPatternOccurrencesPerSession,
                    maximumWorkSetsPerPrimaryMusclePerSession,
                    minimumRecoveryHoursBetweenPrimaryMuscleSessions,
                    List.of());
        }

        public Optional<WeeklyMovementPatternTargetSet> weeklyTargetsFor(int sessionsPerWeek) {
            return weeklyMovementPatternTargets.stream()
                    .filter(target -> target.sessionsPerWeek() == sessionsPerWeek)
                    .findFirst();
        }
    }

    public record WeeklyMovementPatternTargetSet(
            int sessionsPerWeek,
            List<MovementPatternSessionTarget> targets) {
        public WeeklyMovementPatternTargetSet {
            positive(sessionsPerWeek, "weekly movement pattern target frequency");
            targets = List.copyOf(Objects.requireNonNull(targets, "movement pattern targets must not be null"));
            if (targets.isEmpty()) {
                throw new IllegalArgumentException("movement pattern targets must not be empty");
            }
            Set<String> patterns = new HashSet<>();
            targets.forEach(target -> {
                Objects.requireNonNull(target, "movement pattern target must not be null");
                if (!patterns.add(target.movementPattern())) {
                    throw new IllegalArgumentException("movement pattern targets must be unique");
                }
            });
        }

        public boolean appliesTo(Set<String> availableMovementPatterns) {
            return targets.stream().allMatch(target -> availableMovementPatterns.contains(target.movementPattern()));
        }
    }

    public record MovementPatternSessionTarget(
            String movementPattern,
            int minimumSessions,
            int maximumSessions) {
        public MovementPatternSessionTarget {
            if (movementPattern == null || movementPattern.isBlank()) {
                throw new IllegalArgumentException("movement pattern is required");
            }
            positiveRange(minimumSessions, maximumSessions, "movement pattern weekly sessions");
        }
    }

    public record SessionComposition(
            int accessoryWorkSets,
            int accessoryRepMin,
            int accessoryRepMax,
            int accessoryRestSeconds,
            List<ExerciseCountTarget> targetExercisesByMinutes) {
        public SessionComposition {
            positive(accessoryWorkSets, "accessory work sets");
            positiveRange(accessoryRepMin, accessoryRepMax, "accessory repetitions");
            positive(accessoryRestSeconds, "accessory rest seconds");
            targetExercisesByMinutes = List.copyOf(Objects.requireNonNull(
                    targetExercisesByMinutes, "exercise count targets must not be null"));
            Set<Integer> sessionMinutes = new HashSet<>();
            targetExercisesByMinutes.forEach(target -> {
                Objects.requireNonNull(target, "exercise count target must not be null");
                if (!sessionMinutes.add(target.sessionMinutes())) {
                    throw new IllegalArgumentException("exercise count target session minutes must be unique");
                }
            });
        }

        public SessionComposition(
                int accessoryWorkSets,
                int accessoryRepMin,
                int accessoryRepMax,
                int accessoryRestSeconds) {
            this(
                    accessoryWorkSets,
                    accessoryRepMin,
                    accessoryRepMax,
                    accessoryRestSeconds,
                    List.of(new ExerciseCountTarget(45, 4, 5)));
        }

        public Optional<ExerciseCountTarget> targetForMinutes(int sessionMinutes) {
            return targetExercisesByMinutes.stream()
                    .filter(target -> target.sessionMinutes() == sessionMinutes)
                    .findFirst();
        }

        public static SessionComposition defaults() {
            return new SessionComposition(
                    2,
                    8,
                    12,
                    60,
                    List.of(new ExerciseCountTarget(45, 4, 5)));
        }
    }

    public record ExerciseCountTarget(int sessionMinutes, int minimumExercises, int maximumExercises) {
        public ExerciseCountTarget {
            positive(sessionMinutes, "target session minutes");
            positiveRange(minimumExercises, maximumExercises, "target exercises");
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

    public record Warmup(
            int maximumRampSets,
            List<BigDecimal> knownWorkWeightRatios,
            List<Integer> rampSetReps,
            Set<String> eligibleLoadedCompoundMovementPatterns,
            String unknownWeightResult,
            boolean countsTowardTrainingVolume) {
        public Warmup {
            positive(maximumRampSets, "maximum ramp warmup sets");
            knownWorkWeightRatios = List.copyOf(Objects.requireNonNull(
                    knownWorkWeightRatios, "warmup ratios must not be null"));
            if (knownWorkWeightRatios.isEmpty() || knownWorkWeightRatios.size() > maximumRampSets
                    || knownWorkWeightRatios.stream().anyMatch(ratio -> ratio == null
                            || ratio.signum() <= 0 || ratio.compareTo(BigDecimal.ONE) >= 0)) {
                throw new IllegalArgumentException("warmup ratios must be between zero and one");
            }
            rampSetReps = List.copyOf(Objects.requireNonNull(rampSetReps, "warmup repetitions must not be null"));
            if (rampSetReps.size() != knownWorkWeightRatios.size()
                    || rampSetReps.stream().anyMatch(reps -> reps == null || reps <= 0)) {
                throw new IllegalArgumentException("warmup repetitions must align with warmup ratios");
            }
            eligibleLoadedCompoundMovementPatterns = Set.copyOf(Objects.requireNonNull(
                    eligibleLoadedCompoundMovementPatterns,
                    "eligible loaded compound movement patterns must not be null"));
            if (eligibleLoadedCompoundMovementPatterns.isEmpty()
                    || eligibleLoadedCompoundMovementPatterns.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("eligible loaded compound movement patterns are required");
            }
            unknownWeightResult = requiredText(unknownWeightResult, "unknown weight warmup result");
            if (countsTowardTrainingVolume) {
                throw new IllegalArgumentException("warmup must not count toward official training volume");
            }
        }

        public static Warmup defaults() {
            return new Warmup(
                    3,
                    List.of(new BigDecimal("0.50"), new BigDecimal("0.70"), new BigDecimal("0.85")),
                    List.of(10, 6, 3),
                    Set.of("SQUAT", "HINGE", "HORIZONTAL_PUSH", "HORIZONTAL_PULL", "VERTICAL_PUSH", "VERTICAL_PULL"),
                    "CALIBRATION_STEPS_ONLY",
                    false);
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

    private static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value;
    }
}
