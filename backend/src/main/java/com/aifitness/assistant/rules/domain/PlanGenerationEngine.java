package com.aifitness.assistant.rules.domain;

import com.aifitness.assistant.common.domain.RuleReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class PlanGenerationEngine {

    private final PlanValidationEngine validator;

    public PlanGenerationEngine(PlanValidationEngine validator) {
        this.validator = Objects.requireNonNull(validator, "validator must not be null");
    }

    public GenerationResult generate(GenerationInput input) {
        Objects.requireNonNull(input, "input must not be null");
        List<Template> frequencyMatches = input.templates().stream()
                .filter(template -> template.sessionsPerWeek() == input.sessionsPerWeek())
                .sorted(Comparator.comparing(Template::code))
                .toList();
        if (frequencyMatches.isEmpty()) {
            return unavailable("NO_TEMPLATE_FOR_FREQUENCY", "/sessionsPerWeek", input.lockedNumbers());
        }
        Optional<Template> eligible = frequencyMatches.stream()
                .filter(template -> input.eligibleExercises().keySet().containsAll(template.exerciseCodes()))
                .sorted(Comparator.comparingInt(Template::equipmentCalibrationSlots)
                        .reversed()
                        .thenComparing(Template::code))
                .findFirst();
        if (eligible.isEmpty()) {
            return unavailable("NO_ELIGIBLE_TEMPLATE", "/equipment", input.lockedNumbers());
        }

        MergeResult merge = mergeLocks(candidate(eligible.orElseThrow(), input.ruleReference()), input.lockedNumbers());
        if (!merge.issues().isEmpty()) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), merge.issues(), merge.outcomes());
        }
        List<ValidationIssue> issues = validator.validate(
                merge.candidate(), input.sessionMinutes(), input.eligibleExercises());
        if (issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.ERROR)) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), issues, merge.outcomes());
        }
        return new GenerationResult(
                GenerationStatus.CANDIDATE_READY, Optional.of(merge.candidate()), issues, merge.outcomes());
    }

    private static GenerationResult unavailable(
            String reasonCode, String fieldPath, Map<String, Integer> lockedNumbers) {
        Map<String, LockStatus> outcomes = new LinkedHashMap<>();
        lockedNumbers.keySet().stream().sorted().forEach(path -> outcomes.put(path, LockStatus.USER_LOCKED));
        return new GenerationResult(
                GenerationStatus.NO_CANDIDATE,
                Optional.empty(),
                List.of(new ValidationIssue(ValidationSeverity.ERROR, reasonCode, fieldPath)),
                outcomes);
    }

    private static Candidate candidate(Template template, RuleReference reference) {
        return new Candidate(template.code(), template.name(), template.days(), WeightUnit.KG, reference);
    }

    private static MergeResult mergeLocks(Candidate source, Map<String, Integer> lockedNumbers) {
        Candidate candidate = source;
        List<ValidationIssue> issues = new ArrayList<>();
        Map<String, LockStatus> outcomes = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> lock : lockedNumbers.entrySet().stream()
                .sorted(Map.Entry.comparingByKey()).toList()) {
            outcomes.put(lock.getKey(), LockStatus.USER_LOCKED);
            Optional<Candidate> merged = mergeNumber(candidate, lock.getKey(), lock.getValue());
            if (merged.isEmpty()) {
                issues.add(new ValidationIssue(
                        ValidationSeverity.ERROR, "LOCKED_FIELD_PATH_NOT_FOUND", lock.getKey()));
            } else {
                candidate = merged.orElseThrow();
            }
        }
        return new MergeResult(candidate, List.copyOf(issues), Map.copyOf(outcomes));
    }

    private static Optional<Candidate> mergeNumber(Candidate source, String path, int value) {
        List<Day> days = new ArrayList<>();
        boolean matched = false;
        for (Day day : source.days()) {
            List<Exercise> exercises = new ArrayList<>();
            for (Exercise exercise : day.exercises()) {
                String base = "/days/" + day.code() + "/exercises/" + exercise.exerciseCode();
                Exercise merged = exercise;
                if (path.equals(base + "/workSets")) {
                    merged = exercise.withWorkSets(value);
                    matched = true;
                } else if (path.equals(base + "/repMin")) {
                    merged = exercise.withRepMin(value);
                    matched = true;
                } else if (path.equals(base + "/repMax")) {
                    merged = exercise.withRepMax(value);
                    matched = true;
                } else if (path.equals(base + "/restSeconds")) {
                    merged = exercise.withRestSeconds(value);
                    matched = true;
                }
                exercises.add(merged);
            }
            days.add(new Day(day.code(), day.name(), exercises));
        }
        return matched
                ? Optional.of(new Candidate(
                        source.templateCode(), source.name(), days, source.unit(), source.ruleReference()))
                : Optional.empty();
    }

    private record MergeResult(Candidate candidate, List<ValidationIssue> issues, Map<String, LockStatus> outcomes) {}

    public record GenerationInput(
            RuleReference ruleReference,
            int sessionsPerWeek,
            int sessionMinutes,
            List<Template> templates,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
            Map<String, Integer> lockedNumbers) {
        public GenerationInput {
            Objects.requireNonNull(ruleReference, "ruleReference must not be null");
            if (sessionsPerWeek < 2 || sessionsPerWeek > 6) {
                throw new IllegalArgumentException("sessionsPerWeek must be between 2 and 6");
            }
            if (sessionMinutes <= 0) {
                throw new IllegalArgumentException("sessionMinutes must be positive");
            }
            templates = List.copyOf(Objects.requireNonNull(templates, "templates must not be null"));
            eligibleExercises = Map.copyOf(
                    Objects.requireNonNull(eligibleExercises, "eligible exercises must not be null"));
            lockedNumbers = Map.copyOf(Objects.requireNonNull(lockedNumbers, "locked numbers must not be null"));
        }
    }

    public record Template(String code, String name, int sessionsPerWeek, List<Day> days) {
        public Template {
            requireText(code, "template code");
            requireText(name, "template name");
            if (sessionsPerWeek < 2 || sessionsPerWeek > 6) {
                throw new IllegalArgumentException("template frequency must be between 2 and 6");
            }
            days = List.copyOf(Objects.requireNonNull(days, "template days must not be null"));
            if (days.size() != sessionsPerWeek) {
                throw new IllegalArgumentException("template days must match its weekly frequency");
            }
        }

        Set<String> exerciseCodes() {
            return days.stream().flatMap(day -> day.exercises().stream())
                    .map(Exercise::exerciseCode).collect(java.util.stream.Collectors.toUnmodifiableSet());
        }

        int equipmentCalibrationSlots() {
            return (int) days.stream().flatMap(day -> day.exercises().stream())
                    .filter(exercise -> exercise.weightStatus() == WeightStatus.NEEDS_CALIBRATION)
                    .count();
        }
    }

    public record Candidate(
            String templateCode, String name, List<Day> days, WeightUnit unit, RuleReference ruleReference) {
        public Candidate {
            requireText(templateCode, "template code");
            requireText(name, "candidate name");
            days = List.copyOf(Objects.requireNonNull(days, "candidate days must not be null"));
            Objects.requireNonNull(unit, "weight unit must not be null");
            Objects.requireNonNull(ruleReference, "ruleReference must not be null");
        }

        public Candidate(String templateCode, String name, List<Day> days, RuleReference ruleReference) {
            this(templateCode, name, days, WeightUnit.KG, ruleReference);
        }
    }

    public record Day(String code, String name, List<Exercise> exercises) {
        public Day {
            requireText(code, "day code");
            requireText(name, "day name");
            exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
        }
    }

    public record Exercise(
            String exerciseCode,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            WeightStatus weightStatus) {
        public Exercise {
            requireText(exerciseCode, "exercise code");
            Objects.requireNonNull(weightStatus, "weight status must not be null");
        }

        Exercise withWorkSets(int value) {
            return new Exercise(exerciseCode, value, repMin, repMax, restSeconds, weightStatus);
        }

        Exercise withRepMin(int value) {
            return new Exercise(exerciseCode, workSets, value, repMax, restSeconds, weightStatus);
        }

        Exercise withRepMax(int value) {
            return new Exercise(exerciseCode, workSets, repMin, value, restSeconds, weightStatus);
        }

        Exercise withRestSeconds(int value) {
            return new Exercise(exerciseCode, workSets, repMin, repMax, value, weightStatus);
        }
    }

    public record ValidationIssue(ValidationSeverity severity, String reasonCode, String fieldPath) {
        public ValidationIssue {
            Objects.requireNonNull(severity, "severity must not be null");
            requireText(reasonCode, "reason code");
            requireText(fieldPath, "field path");
        }
    }

    public record GenerationResult(
            GenerationStatus status,
            Optional<Candidate> candidate,
            List<ValidationIssue> issues,
            Map<String, LockStatus> lockedFieldOutcomes) {
        public GenerationResult {
            Objects.requireNonNull(status, "generation status must not be null");
            candidate = Objects.requireNonNull(candidate, "candidate must not be null");
            issues = List.copyOf(Objects.requireNonNull(issues, "issues must not be null"));
            lockedFieldOutcomes = Map.copyOf(
                    Objects.requireNonNull(lockedFieldOutcomes, "locked outcomes must not be null"));
        }
    }

    public enum GenerationStatus { CANDIDATE_READY, NO_CANDIDATE }
    public enum ValidationSeverity { WARNING, ERROR }
    public enum WeightStatus { KNOWN, NEEDS_CALIBRATION, BODYWEIGHT }
    public enum WeightUnit { KG, LB }
    public enum LockStatus { USER_LOCKED }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
