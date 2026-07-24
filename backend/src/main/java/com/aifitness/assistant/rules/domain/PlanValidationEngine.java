package com.aifitness.assistant.rules.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PlanValidationEngine {

    private static final int HOURS_PER_WEEK = 168;

    private final PlanRulePolicy policy;

    public PlanValidationEngine(PlanRulePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public List<PlanGenerationEngine.ValidationIssue> validate(
            PlanGenerationEngine.Candidate candidate,
            int availableMinutes,
            Map<String, ExerciseFacts> eligibleExercises) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Map<String, ExerciseFacts> facts = Map.copyOf(
                Objects.requireNonNull(eligibleExercises, "eligible exercises must not be null"));
        if (availableMinutes <= 0) {
            throw new IllegalArgumentException("available minutes must be positive");
        }
        List<PlanGenerationEngine.ValidationIssue> issues = new ArrayList<>();
        if (!policy.version().equals(candidate.ruleReference().ruleVersion())) {
            issues.add(error("RULE_VERSION_NOT_SUPPORTED", "/ruleReference/ruleVersion"));
        }
        if (candidate.unit() != PlanGenerationEngine.WeightUnit.KG) {
            issues.add(error("P0_UNIT_NOT_SUPPORTED", "/unit"));
        }
        PlanRulePolicy.PlanLimits limits = policy.planLimits();
        if (candidate.days().size() < limits.minimumSessionsPerWeek()
                || candidate.days().size() > limits.maximumSessionsPerWeek()) {
            issues.add(error("SESSION_FREQUENCY_OUT_OF_RANGE", "/days"));
        }
        boolean calibrationRequired = false;
        Map<String, Integer> lastPrimaryMuscleSession = new HashMap<>();
        for (int dayIndex = 0; dayIndex < candidate.days().size(); dayIndex++) {
            PlanGenerationEngine.Day day = candidate.days().get(dayIndex);
            String dayPath = "/days/" + day.code();
            if (day.exercises().isEmpty() || day.exercises().size() > limits.maximumExercisesPerSession()) {
                issues.add(error("EXERCISE_COUNT_OUT_OF_RANGE", dayPath + "/exercises"));
            }
            int estimatedSeconds = 0;
            Map<String, Integer> movementCounts = new HashMap<>();
            Map<String, Integer> primaryMuscleSets = new HashMap<>();
            Set<String> dayMuscles = new HashSet<>();
            Set<String> exerciseCodes = new HashSet<>();
            for (PlanGenerationEngine.Exercise exercise : day.exercises()) {
                String exercisePath = dayPath + "/exercises/" + exercise.exerciseCode();
                validatePrescription(exercise, exercisePath, issues);
                if (!exerciseCodes.add(exercise.exerciseCode())) {
                    issues.add(error("DUPLICATE_EXERCISE", exercisePath));
                }
                ExerciseFacts exerciseFacts = facts.get(exercise.exerciseCode());
                if (exerciseFacts == null) {
                    issues.add(error("EXERCISE_NOT_ELIGIBLE", exercisePath));
                } else {
                    movementCounts.merge(exerciseFacts.movementPattern(), 1, Integer::sum);
                    exerciseFacts.primaryMuscles().forEach(muscle -> {
                        primaryMuscleSets.merge(muscle, exercise.workSets(), Integer::sum);
                        dayMuscles.add(muscle);
                    });
                }
                calibrationRequired |= exercise.weightStatus()
                        == PlanGenerationEngine.WeightStatus.NEEDS_CALIBRATION;
                estimatedSeconds += exercise.workSets()
                        * (policy.duration().secondsPerWorkSet() + exercise.restSeconds())
                        + policy.duration().secondsPerExerciseTransition();
            }
            movementCounts.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String pattern = entry.getKey();
                int count = entry.getValue();
                if (count > policy.balance().maximumMovementPatternOccurrencesPerSession()) {
                    issues.add(error("DUPLICATE_MOVEMENT_PATTERN", dayPath + "/movementPatterns/" + pattern));
                }
            });
            primaryMuscleSets.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
                String muscle = entry.getKey();
                int sets = entry.getValue();
                if (sets > policy.balance().maximumWorkSetsPerPrimaryMusclePerSession()) {
                    issues.add(error("PRIMARY_MUSCLE_VOLUME_OUT_OF_RANGE", dayPath + "/primaryMuscles/" + muscle));
                }
            });
            int sessionIndex = dayIndex;
            dayMuscles.stream().sorted().forEach(muscle -> {
                Integer previous = lastPrimaryMuscleSession.put(muscle, sessionIndex);
                int estimatedHours = previous == null || candidate.days().isEmpty()
                        ? Integer.MAX_VALUE
                        : (HOURS_PER_WEEK * (sessionIndex - previous)) / candidate.days().size();
                if (estimatedHours < policy.balance().minimumRecoveryHoursBetweenPrimaryMuscleSessions()) {
                    issues.add(warning("RECOVERY_WINDOW_TOO_SHORT", dayPath + "/primaryMuscles/" + muscle));
                }
            });
            int maximumMinutes = Math.min(availableMinutes, limits.maximumEstimatedMinutes());
            if (estimatedSeconds > maximumMinutes * 60) {
                issues.add(error("SESSION_DURATION_EXCEEDED", dayPath));
            }
        }
        if (issues.stream().noneMatch(issue -> issue.severity() == PlanGenerationEngine.ValidationSeverity.ERROR)
                && calibrationRequired) {
            issues.add(new PlanGenerationEngine.ValidationIssue(
                    PlanGenerationEngine.ValidationSeverity.WARNING,
                    "INITIAL_WEIGHT_NEEDS_CALIBRATION",
                    "/days"));
        }
        return List.copyOf(issues);
    }

    private void validatePrescription(
            PlanGenerationEngine.Exercise exercise,
            String path,
            List<PlanGenerationEngine.ValidationIssue> issues) {
        PlanRulePolicy.Prescription prescription = policy.prescription();
        if (exercise.workSets() < prescription.minimumWorkSets()
                || exercise.workSets() > prescription.maximumWorkSets()) {
            issues.add(error("WORK_SETS_OUT_OF_RANGE", path + "/workSets"));
        }
        if (exercise.repMin() < prescription.minimumReps()
                || exercise.repMax() > prescription.maximumReps()
                || exercise.repMin() > exercise.repMax()) {
            issues.add(error("REP_RANGE_OUT_OF_RANGE", path + "/repRange"));
        }
        if (exercise.restSeconds() < policy.rest().minimumSeconds()
                || exercise.restSeconds() > policy.rest().maximumSeconds()) {
            issues.add(error("REST_OUT_OF_RANGE", path + "/restSeconds"));
        }
    }

    private static PlanGenerationEngine.ValidationIssue error(String reasonCode, String fieldPath) {
        return new PlanGenerationEngine.ValidationIssue(
                PlanGenerationEngine.ValidationSeverity.ERROR, reasonCode, fieldPath);
    }

    private static PlanGenerationEngine.ValidationIssue warning(String reasonCode, String fieldPath) {
        return new PlanGenerationEngine.ValidationIssue(
                PlanGenerationEngine.ValidationSeverity.WARNING, reasonCode, fieldPath);
    }

    public record ExerciseFacts(String movementPattern, Set<String> primaryMuscles) {
        public ExerciseFacts {
            if (movementPattern == null || movementPattern.isBlank()) {
                throw new IllegalArgumentException("movement pattern is required");
            }
            primaryMuscles = Set.copyOf(
                    Objects.requireNonNull(primaryMuscles, "primary muscles must not be null"));
            if (primaryMuscles.isEmpty() || primaryMuscles.stream().anyMatch(String::isBlank)) {
                throw new IllegalArgumentException("primary muscles must contain values");
            }
        }
    }
}
