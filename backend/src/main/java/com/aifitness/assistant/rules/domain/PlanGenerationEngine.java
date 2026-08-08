package com.aifitness.assistant.rules.domain;

import com.aifitness.assistant.common.domain.RuleReference;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
                .filter(template -> adaptable(template, input))
                .sorted(Comparator.comparingInt((Template template) -> directEligibleSlots(template, input))
                        .reversed()
                        .thenComparing(Comparator.comparingInt(Template::equipmentCalibrationSlots)
                        .reversed()
                        .thenComparing(Template::code)))
                .findFirst();
        if (eligible.isEmpty()) {
            return unavailable("NO_ELIGIBLE_TEMPLATE", "/equipment", input.lockedNumbers());
        }

        PersonalizedCandidate personalized = personalize(eligible.orElseThrow(), input);
        MergeResult merge = mergeLocks(personalized.candidate(), input.lockedNumbers());
        if (!merge.issues().isEmpty()) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), merge.issues(), merge.outcomes());
        }
        List<ValidationIssue> issues = new ArrayList<>(personalized.issues());
        issues.addAll(validator.validate(
                merge.candidate(), input.sessionMinutes(), input.eligibleExercises()));
        if (issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.ERROR)) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), List.copyOf(issues), merge.outcomes());
        }
        return new GenerationResult(
                GenerationStatus.CANDIDATE_READY,
                Optional.of(merge.candidate()),
                List.copyOf(issues),
                merge.outcomes());
    }

    public GenerationResult evaluate(
            Candidate candidate,
            int sessionMinutes,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
            Map<String, Integer> lockedNumbers) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Map<String, PlanValidationEngine.ExerciseFacts> exerciseFacts = Map.copyOf(
                Objects.requireNonNull(eligibleExercises, "eligible exercises must not be null"));
        Map<String, Integer> locks = lockedNumbers == null ? Map.of() : Map.copyOf(lockedNumbers);
        MergeResult merge = mergeLocks(candidate, locks);
        if (!merge.issues().isEmpty()) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), merge.issues(), merge.outcomes());
        }
        List<ValidationIssue> issues = validator.validate(
                merge.candidate(), sessionMinutes, exerciseFacts);
        if (issues.stream().anyMatch(issue -> issue.severity() == ValidationSeverity.ERROR)) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), issues, merge.outcomes());
        }
        return new GenerationResult(
                GenerationStatus.CANDIDATE_READY,
                Optional.of(merge.candidate()),
                issues,
                merge.outcomes());
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

    private static boolean adaptable(Template template, GenerationInput input) {
        if (input.eligibleExercises().isEmpty()) {
            return false;
        }
        return template.days().stream().flatMap(day -> day.exercises().stream()).allMatch(exercise -> {
            if (input.eligibleExercises().containsKey(exercise.exerciseCode())) {
                return true;
            }
            PlanValidationEngine.ExerciseFacts original = input.catalogExercises().get(exercise.exerciseCode());
            return original != null && input.eligibleExercises().values().stream()
                    .anyMatch(candidate -> candidate.movementPattern().equals(original.movementPattern()));
        });
    }

    private static int directEligibleSlots(Template template, GenerationInput input) {
        return (int) template.days().stream().flatMap(day -> day.exercises().stream())
                .filter(exercise -> input.eligibleExercises().containsKey(exercise.exerciseCode()))
                .count();
    }

    private static PersonalizedCandidate personalize(Template template, GenerationInput input) {
        int maximumExercises = input.policy().planLimits().maximumExercisesPerSession();
        int availableSeconds = Math.min(
                input.sessionMinutes(),
                input.policy().planLimits().maximumEstimatedMinutes()) * 60;
        Map<String, Integer> globalUsage = new HashMap<>();
        Map<String, Integer> firstPrimaryMuscleSession = new HashMap<>();
        Map<String, Integer> lastPrimaryMuscleSession = new HashMap<>();
        List<Day> days = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < template.days().size(); dayIndex++) {
            Day sourceDay = template.days().get(dayIndex);
            List<Exercise> selected = new ArrayList<>();
            Set<String> usedCodes = new HashSet<>();
            Set<String> usedPatterns = new HashSet<>();
            Map<String, Integer> muscleSets = new HashMap<>();
            List<Exercise> prioritizedSources = sourceDay.exercises().stream()
                    .sorted(templateExerciseComparator(input.goal(), input.catalogExercises()))
                    .toList();
            for (Exercise source : prioritizedSources) {
                if (selected.size() >= maximumExercises) {
                    break;
                }
                Optional<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> resolved =
                        resolveTemplateExercise(
                                source,
                                input,
                                usedCodes,
                                globalUsage,
                                dayIndex,
                                template,
                                firstPrimaryMuscleSession,
                                lastPrimaryMuscleSession);
                if (resolved.isEmpty()) {
                    continue;
                }
                Map.Entry<String, PlanValidationEngine.ExerciseFacts> entry = resolved.orElseThrow();
                PlanValidationEngine.ExerciseFacts facts = entry.getValue();
                PlanRulePolicy.GoalPrescription prescription =
                        input.policy().goalPrescriptions().get(input.goal());
                Exercise exercise = new Exercise(
                        entry.getKey(),
                        prescription.workSets(),
                        prescription.repMin(),
                        prescription.repMax(),
                        prescription.restSeconds(),
                        facts.bodyweight() ? WeightStatus.BODYWEIGHT : WeightStatus.NEEDS_CALIBRATION);
                if (estimatedSeconds(selected, input.policy()) + estimatedSeconds(exercise, input.policy())
                        > availableSeconds) {
                    continue;
                }
                selected.add(exercise);
                register(exercise, facts, usedCodes, usedPatterns, muscleSets, globalUsage);
            }
            while (selected.size() < maximumExercises) {
                Optional<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> accessory =
                        selectAccessory(
                                input,
                                usedCodes,
                                usedPatterns,
                                muscleSets,
                                globalUsage,
                                dayIndex,
                                template,
                                firstPrimaryMuscleSession,
                                lastPrimaryMuscleSession);
                if (accessory.isEmpty()) {
                    break;
                }
                Map.Entry<String, PlanValidationEngine.ExerciseFacts> entry = accessory.orElseThrow();
                PlanRulePolicy.SessionComposition composition = input.policy().sessionComposition();
                Exercise exercise = new Exercise(
                        entry.getKey(),
                        composition.accessoryWorkSets(),
                        composition.accessoryRepMin(),
                        composition.accessoryRepMax(),
                        composition.accessoryRestSeconds(),
                        entry.getValue().bodyweight()
                                ? WeightStatus.BODYWEIGHT
                                : WeightStatus.NEEDS_CALIBRATION);
                if (estimatedSeconds(selected, input.policy()) + estimatedSeconds(exercise, input.policy())
                        > availableSeconds) {
                    break;
                }
                selected.add(exercise);
                register(
                        exercise,
                        entry.getValue(),
                        usedCodes,
                        usedPatterns,
                        muscleSets,
                        globalUsage);
            }
            days.add(new Day(sourceDay.code(), sourceDay.name(), selected));
            registerDayMuscles(
                    selected,
                    input.eligibleExercises(),
                    dayIndex,
                    firstPrimaryMuscleSession,
                    lastPrimaryMuscleSession);
        }
        return new PersonalizedCandidate(
                new Candidate(template.code(), template.name(), days, WeightUnit.KG, input.ruleReference()),
                List.of());
    }

    private static Optional<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> resolveTemplateExercise(
            Exercise source,
            GenerationInput input,
            Set<String> usedCodes,
            Map<String, Integer> globalUsage,
            int dayIndex,
            Template template,
            Map<String, Integer> firstPrimaryMuscleSession,
            Map<String, Integer> lastPrimaryMuscleSession) {
        PlanValidationEngine.ExerciseFacts direct = input.eligibleExercises().get(source.exerciseCode());
        if (direct != null && !usedCodes.contains(source.exerciseCode())) {
            return Optional.of(Map.entry(source.exerciseCode(), direct));
        }
        PlanValidationEngine.ExerciseFacts original = input.catalogExercises().get(source.exerciseCode());
        if (original == null) {
            return Optional.empty();
        }
        return input.eligibleExercises().entrySet().stream()
                .filter(entry -> !usedCodes.contains(entry.getKey()))
                .filter(entry -> entry.getValue().movementPattern().equals(original.movementPattern()))
                .filter(entry -> recoverySafe(
                        entry.getValue(),
                        dayIndex,
                        template.days().size(),
                        firstPrimaryMuscleSession,
                        lastPrimaryMuscleSession,
                        input.policy()))
                .sorted(exerciseComparator(input.goal(), globalUsage))
                .findFirst();
    }

    private static Optional<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> selectAccessory(
            GenerationInput input,
            Set<String> usedCodes,
            Set<String> usedPatterns,
            Map<String, Integer> muscleSets,
            Map<String, Integer> globalUsage,
            int dayIndex,
            Template template,
            Map<String, Integer> firstPrimaryMuscleSession,
            Map<String, Integer> lastPrimaryMuscleSession) {
        int accessorySets = input.policy().sessionComposition().accessoryWorkSets();
        int maximumMuscleSets = input.policy().balance().maximumWorkSetsPerPrimaryMusclePerSession();
        return input.eligibleExercises().entrySet().stream()
                .filter(entry -> !usedCodes.contains(entry.getKey()))
                .filter(entry -> !usedPatterns.contains(entry.getValue().movementPattern()))
                .filter(entry -> entry.getValue().primaryMuscles().stream()
                        .allMatch(muscle -> muscleSets.getOrDefault(muscle, 0) + accessorySets
                                <= maximumMuscleSets))
                .filter(entry -> recoverySafe(
                        entry.getValue(),
                        dayIndex,
                        template.days().size(),
                        firstPrimaryMuscleSession,
                        lastPrimaryMuscleSession,
                        input.policy()))
                .filter(entry -> templateRecoverySafe(entry.getValue(), dayIndex, template, input))
                .sorted(exerciseComparator(input.goal(), globalUsage))
                .findFirst();
    }

    private static boolean recoverySafe(
            PlanValidationEngine.ExerciseFacts facts,
            int dayIndex,
            int sessionsPerWeek,
            Map<String, Integer> firstPrimaryMuscleSession,
            Map<String, Integer> lastPrimaryMuscleSession,
            PlanRulePolicy policy) {
        return facts.primaryMuscles().stream().allMatch(muscle -> {
            Integer previous = lastPrimaryMuscleSession.get(muscle);
            Integer first = firstPrimaryMuscleSession.get(muscle);
            int minimumHours = policy.balance().minimumRecoveryHoursBetweenPrimaryMuscleSessions();
            boolean previousSafe = previous == null
                    || (168 * (dayIndex - previous)) / sessionsPerWeek >= minimumHours;
            boolean nextWeekSafe = first == null
                    || (168 * (sessionsPerWeek - dayIndex + first)) / sessionsPerWeek >= minimumHours;
            return previousSafe && nextWeekSafe;
        });
    }

    private static boolean templateRecoverySafe(
            PlanValidationEngine.ExerciseFacts candidateFacts,
            int dayIndex,
            Template template,
            GenerationInput input) {
        int minimumHours = input.policy().balance().minimumRecoveryHoursBetweenPrimaryMuscleSessions();
        int sessionsPerWeek = template.days().size();
        for (int otherDayIndex = 0; otherDayIndex < sessionsPerWeek; otherDayIndex++) {
            if (otherDayIndex == dayIndex) {
                continue;
            }
            int forwardSessions = Math.floorMod(otherDayIndex - dayIndex, sessionsPerWeek);
            int nearestSessions = Math.min(forwardSessions, sessionsPerWeek - forwardSessions);
            if ((168 * nearestSessions) / sessionsPerWeek >= minimumHours) {
                continue;
            }
            for (Exercise futureExercise : template.days().get(otherDayIndex).exercises()) {
                PlanValidationEngine.ExerciseFacts futureFacts =
                        input.catalogExercises().get(futureExercise.exerciseCode());
                if (futureFacts != null
                        && futureFacts.primaryMuscles().stream()
                                .anyMatch(candidateFacts.primaryMuscles()::contains)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static void registerDayMuscles(
            List<Exercise> exercises,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
            int dayIndex,
            Map<String, Integer> firstPrimaryMuscleSession,
            Map<String, Integer> lastPrimaryMuscleSession) {
        exercises.stream()
                .map(Exercise::exerciseCode)
                .map(eligibleExercises::get)
                .filter(Objects::nonNull)
                .flatMap(facts -> facts.primaryMuscles().stream())
                .forEach(muscle -> {
                    firstPrimaryMuscleSession.putIfAbsent(muscle, dayIndex);
                    lastPrimaryMuscleSession.put(muscle, dayIndex);
                });
    }

    private static Comparator<Exercise> templateExerciseComparator(
            FitnessGoal goal,
            Map<String, PlanValidationEngine.ExerciseFacts> catalogExercises) {
        return Comparator
                .comparingInt((Exercise exercise) -> {
                    PlanValidationEngine.ExerciseFacts facts = catalogExercises.get(exercise.exerciseCode());
                    return facts == null ? Integer.MAX_VALUE : movementPriority(goal, facts.movementPattern());
                })
                .thenComparing(Exercise::exerciseCode);
    }

    private static Comparator<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> exerciseComparator(
            FitnessGoal goal, Map<String, Integer> globalUsage) {
        return Comparator
                .comparingInt((Map.Entry<String, PlanValidationEngine.ExerciseFacts> entry) ->
                        globalUsage.getOrDefault(entry.getKey(), 0))
                .thenComparingInt(entry -> movementPriority(goal, entry.getValue().movementPattern()))
                .thenComparing(Map.Entry::getKey);
    }

    private static int movementPriority(FitnessGoal goal, String pattern) {
        List<String> priorities = switch (goal) {
            case STRENGTH -> List.of(
                    "SQUAT",
                    "HINGE",
                    "HORIZONTAL_PUSH",
                    "HORIZONTAL_PULL",
                    "VERTICAL_PUSH",
                    "VERTICAL_PULL",
                    "CORE");
            case HYPERTROPHY -> List.of(
                    "HORIZONTAL_PUSH",
                    "HORIZONTAL_PULL",
                    "VERTICAL_PUSH",
                    "VERTICAL_PULL",
                    "SQUAT",
                    "HINGE",
                    "CORE");
            case GENERAL_FITNESS -> List.of(
                    "CORE",
                    "SQUAT",
                    "HINGE",
                    "HORIZONTAL_PULL",
                    "HORIZONTAL_PUSH",
                    "VERTICAL_PULL",
                    "VERTICAL_PUSH");
        };
        int index = priorities.indexOf(pattern);
        return index < 0 ? priorities.size() : index;
    }

    private static void register(
            Exercise exercise,
            PlanValidationEngine.ExerciseFacts facts,
            Set<String> usedCodes,
            Set<String> usedPatterns,
            Map<String, Integer> muscleSets,
            Map<String, Integer> globalUsage) {
        usedCodes.add(exercise.exerciseCode());
        usedPatterns.add(facts.movementPattern());
        facts.primaryMuscles().forEach(muscle -> muscleSets.merge(muscle, exercise.workSets(), Integer::sum));
        globalUsage.merge(exercise.exerciseCode(), 1, Integer::sum);
    }

    private static int estimatedSeconds(List<Exercise> exercises, PlanRulePolicy policy) {
        return exercises.stream().mapToInt(exercise -> estimatedSeconds(exercise, policy)).sum();
    }

    private static int estimatedSeconds(Exercise exercise, PlanRulePolicy policy) {
        return exercise.workSets() * (policy.duration().secondsPerWorkSet() + exercise.restSeconds())
                + policy.duration().secondsPerExerciseTransition();
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
    private record PersonalizedCandidate(Candidate candidate, List<ValidationIssue> issues) {}

    public record GenerationInput(
            RuleReference ruleReference,
            int sessionsPerWeek,
            int sessionMinutes,
            ExperienceLevel experience,
            FitnessGoal goal,
            List<Template> templates,
            Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
            Map<String, PlanValidationEngine.ExerciseFacts> catalogExercises,
            PlanRulePolicy policy,
            Map<String, Integer> lockedNumbers) {
        public GenerationInput {
            Objects.requireNonNull(ruleReference, "ruleReference must not be null");
            if (sessionsPerWeek < 2 || sessionsPerWeek > 6) {
                throw new IllegalArgumentException("sessionsPerWeek must be between 2 and 6");
            }
            if (sessionMinutes <= 0) {
                throw new IllegalArgumentException("sessionMinutes must be positive");
            }
            Objects.requireNonNull(experience, "experience must not be null");
            Objects.requireNonNull(goal, "fitness goal must not be null");
            templates = List.copyOf(Objects.requireNonNull(templates, "templates must not be null"));
            eligibleExercises = Map.copyOf(
                    Objects.requireNonNull(eligibleExercises, "eligible exercises must not be null"));
            catalogExercises = Map.copyOf(
                    Objects.requireNonNull(catalogExercises, "catalog exercises must not be null"));
            Objects.requireNonNull(policy, "plan rule policy must not be null");
            lockedNumbers = Map.copyOf(Objects.requireNonNull(lockedNumbers, "locked numbers must not be null"));
        }

        public GenerationInput(
                RuleReference ruleReference,
                int sessionsPerWeek,
                int sessionMinutes,
                List<Template> templates,
                Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
                Map<String, Integer> lockedNumbers) {
            this(
                    ruleReference,
                    sessionsPerWeek,
                    sessionMinutes,
                    ExperienceLevel.BEGINNER,
                    FitnessGoal.GENERAL_FITNESS,
                    templates,
                    eligibleExercises,
                    eligibleExercises,
                    PlanRulePolicyDefaults.LEGACY_POLICY,
                    lockedNumbers);
        }

        public GenerationInput(
                RuleReference ruleReference,
                int sessionsPerWeek,
                int sessionMinutes,
                ExperienceLevel experience,
                FitnessGoal goal,
                List<Template> templates,
                Map<String, PlanValidationEngine.ExerciseFacts> eligibleExercises,
                Map<String, PlanValidationEngine.ExerciseFacts> catalogExercises,
                Map<String, Integer> lockedNumbers) {
            this(
                    ruleReference,
                    sessionsPerWeek,
                    sessionMinutes,
                    experience,
                    goal,
                    templates,
                    eligibleExercises,
                    catalogExercises,
                    PlanRulePolicyDefaults.PERSONALIZED_POLICY,
                    lockedNumbers);
        }
    }

    private static final class PlanRulePolicyDefaults {
        private static final PlanRulePolicy PERSONALIZED_POLICY = new PlanRulePolicy(
                "1.2.0",
                new PlanRulePolicy.PlanLimits(2, 6, 8, 90),
                new PlanRulePolicy.Prescription(2, 4, 5, 15),
                new PlanRulePolicy.Rest(45, 240),
                new PlanRulePolicy.Duration(45, 75),
                new PlanRulePolicy.Balance(1, 12, 48));
        private static final PlanRulePolicy LEGACY_POLICY = new PlanRulePolicy(
                "1.2.0",
                new PlanRulePolicy.PlanLimits(2, 6, 8, 90),
                new PlanRulePolicy.Prescription(2, 4, 5, 15),
                new PlanRulePolicy.Rest(45, 240),
                new PlanRulePolicy.Duration(45, 75),
                new PlanRulePolicy.Balance(1, 12, 48),
                new PlanRulePolicy.SessionComposition(
                        2,
                        8,
                        12,
                        60));

        private PlanRulePolicyDefaults() {}
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
    public enum ExperienceLevel { BEGINNER, INTERMEDIATE, ADVANCED }
    public enum FitnessGoal { STRENGTH, HYPERTROPHY, GENERAL_FITNESS }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
