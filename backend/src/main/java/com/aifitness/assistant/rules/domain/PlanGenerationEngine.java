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
                .sorted(Comparator.comparingInt((Template template) -> directLoadedSlots(template, input))
                        .reversed()
                        .thenComparing(Comparator.comparingInt(
                                        (Template template) -> directEligibleSlots(template, input))
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
        if (issues.stream().anyMatch(PlanGenerationEngine::blocksCandidate)) {
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
        if (issues.stream().anyMatch(PlanGenerationEngine::blocksCandidate)) {
            return new GenerationResult(
                    GenerationStatus.NO_CANDIDATE, Optional.empty(), issues, merge.outcomes());
        }
        return new GenerationResult(
                GenerationStatus.CANDIDATE_READY,
                Optional.of(merge.candidate()),
                issues,
                merge.outcomes());
    }

    private static boolean blocksCandidate(ValidationIssue issue) {
        return issue.severity() == ValidationSeverity.ERROR
                || "RECOVERY_WINDOW_TOO_SHORT".equals(issue.reasonCode());
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

    private static int directLoadedSlots(Template template, GenerationInput input) {
        return (int) template.days().stream().flatMap(day -> day.exercises().stream())
                .filter(exercise -> {
                    PlanValidationEngine.ExerciseFacts facts =
                            input.eligibleExercises().get(exercise.exerciseCode());
                    return facts != null && !facts.bodyweight();
                })
                .count();
    }

    private static PersonalizedCandidate personalize(Template template, GenerationInput input) {
        Optional<PlanRulePolicy.ExerciseCountTarget> exerciseTarget =
                input.policy().sessionComposition().targetForMinutes(input.sessionMinutes());
        int minimumExercises = exerciseTarget.map(PlanRulePolicy.ExerciseCountTarget::minimumExercises)
                .orElse(1);
        int maximumExercises = Math.min(
                input.policy().planLimits().maximumExercisesPerSession(),
                exerciseTarget.map(PlanRulePolicy.ExerciseCountTarget::maximumExercises)
                        .orElse(input.policy().planLimits().maximumExercisesPerSession()));
        int availableSeconds = Math.min(
                input.sessionMinutes(),
                input.policy().planLimits().maximumEstimatedMinutes()) * 60;
        Map<String, Integer> globalUsage = new HashMap<>();
        Map<String, Integer> firstPrimaryMuscleSession = new HashMap<>();
        Map<String, Integer> lastPrimaryMuscleSession = new HashMap<>();
        List<Day> days = new ArrayList<>();
        List<ValidationIssue> issues = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < template.days().size(); dayIndex++) {
            Day sourceDay = template.days().get(dayIndex);
            List<Exercise> selected = new ArrayList<>();
            Set<String> usedCodes = new HashSet<>();
            Map<String, Integer> movementCounts = new HashMap<>();
            Map<String, Integer> muscleSets = new HashMap<>();
            boolean durationLimited = false;
            List<Exercise> prioritizedSources = sourceDay.exercises().stream()
                    .sorted(Comparator
                            .comparingInt((Exercise exercise) -> {
                                PlanValidationEngine.ExerciseFacts facts =
                                        input.catalogExercises().get(exercise.exerciseCode());
                                return facts != null
                                                && sourceDay.focus().requiredPatterns()
                                                        .contains(facts.movementPattern())
                                        ? 0 : 1;
                            })
                            .thenComparing(templateExerciseComparator(
                                    input.goal(), input.catalogExercises())))
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
                                movementCounts,
                                globalUsage,
                                sourceDay.focus(),
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
                if (estimatedSeconds(selected, exercise, input.policy())
                        > availableSeconds) {
                    durationLimited = true;
                    continue;
                }
                selected.add(exercise);
                register(exercise, facts, usedCodes, movementCounts, muscleSets, globalUsage);
            }
            while (selected.size() < minimumExercises) {
                Optional<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> accessory =
                        selectAccessory(
                                input,
                                usedCodes,
                                movementCounts,
                                muscleSets,
                                globalUsage,
                                sourceDay.focus(),
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
                if (estimatedSeconds(selected, exercise, input.policy())
                        > availableSeconds) {
                    durationLimited = true;
                    break;
                }
                selected.add(exercise);
                register(
                        exercise,
                        entry.getValue(),
                        usedCodes,
                        movementCounts,
                        muscleSets,
                        globalUsage);
            }
            if (selected.size() < minimumExercises) {
                issues.add(new ValidationIssue(
                        ValidationSeverity.ERROR,
                        durationLimited
                                ? "SESSION_TARGET_CANNOT_FIT_DURATION"
                                : "INSUFFICIENT_ELIGIBLE_EXERCISES",
                        "/days/" + sourceDay.code() + "/exercises"));
            }
            Set<String> selectedPatterns = selected.stream()
                    .map(Exercise::exerciseCode)
                    .map(input.eligibleExercises()::get)
                    .filter(Objects::nonNull)
                    .map(PlanValidationEngine.ExerciseFacts::movementPattern)
                    .collect(java.util.stream.Collectors.toSet());
            if (!selectedPatterns.containsAll(sourceDay.focus().requiredPatterns())) {
                issues.add(new ValidationIssue(
                        ValidationSeverity.ERROR,
                        "INSUFFICIENT_ELIGIBLE_EXERCISES",
                        "/days/" + sourceDay.code() + "/requiredPatterns"));
            }
            days.add(new Day(sourceDay.code(), sourceDay.name(), selected, sourceDay.focus()));
            registerDayMuscles(
                    selected,
                    input.eligibleExercises(),
                    dayIndex,
                    firstPrimaryMuscleSession,
                    lastPrimaryMuscleSession);
        }
        return new PersonalizedCandidate(
                new Candidate(template.code(), template.name(), days, WeightUnit.KG, input.ruleReference()),
                List.copyOf(issues));
    }

    private static Optional<Map.Entry<String, PlanValidationEngine.ExerciseFacts>> resolveTemplateExercise(
            Exercise source,
            GenerationInput input,
            Set<String> usedCodes,
            Map<String, Integer> movementCounts,
            Map<String, Integer> globalUsage,
            SessionFocus focus,
            int dayIndex,
            Template template,
            Map<String, Integer> firstPrimaryMuscleSession,
            Map<String, Integer> lastPrimaryMuscleSession) {
        PlanValidationEngine.ExerciseFacts direct = input.eligibleExercises().get(source.exerciseCode());
        if (direct != null
                && !usedCodes.contains(source.exerciseCode())
                && focus.allows(direct.movementPattern())
                && movementCounts.getOrDefault(direct.movementPattern(), 0)
                        < focus.maximumPatternOccurrences(
                                direct.movementPattern(),
                                input.policy().balance().maximumMovementPatternOccurrencesPerSession())) {
            return Optional.of(Map.entry(source.exerciseCode(), direct));
        }
        PlanValidationEngine.ExerciseFacts original = input.catalogExercises().get(source.exerciseCode());
        if (original == null) {
            return Optional.empty();
        }
        return input.eligibleExercises().entrySet().stream()
                .filter(entry -> !usedCodes.contains(entry.getKey()))
                .filter(entry -> entry.getValue().movementPattern().equals(original.movementPattern()))
                .filter(entry -> focus.allows(entry.getValue().movementPattern()))
                .filter(entry -> movementCounts.getOrDefault(entry.getValue().movementPattern(), 0)
                        < focus.maximumPatternOccurrences(
                                entry.getValue().movementPattern(),
                                input.policy().balance().maximumMovementPatternOccurrencesPerSession()))
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
            Map<String, Integer> movementCounts,
            Map<String, Integer> muscleSets,
            Map<String, Integer> globalUsage,
            SessionFocus focus,
            int dayIndex,
            Template template,
            Map<String, Integer> firstPrimaryMuscleSession,
            Map<String, Integer> lastPrimaryMuscleSession) {
        int accessorySets = input.policy().sessionComposition().accessoryWorkSets();
        int maximumMuscleSets = input.policy().balance().maximumWorkSetsPerPrimaryMusclePerSession();
        return input.eligibleExercises().entrySet().stream()
                .filter(entry -> !usedCodes.contains(entry.getKey()))
                .filter(entry -> movementCounts.getOrDefault(entry.getValue().movementPattern(), 0)
                        < focus.maximumPatternOccurrences(
                                entry.getValue().movementPattern(),
                                input.policy().balance().maximumMovementPatternOccurrencesPerSession()))
                .filter(entry -> focus.allows(entry.getValue().movementPattern()))
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
                .sorted(Comparator
                        .comparingInt((Map.Entry<String, PlanValidationEngine.ExerciseFacts> entry) ->
                                focus.requiredPatterns().contains(entry.getValue().movementPattern())
                                                && !movementCounts.containsKey(entry.getValue().movementPattern())
                                        ? 0 : 1)
                        .thenComparing(exerciseComparator(input.goal(), globalUsage)))
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
                    "CALF_RAISE",
                    "SHOULDER_ABDUCTION",
                    "SHOULDER_HORIZONTAL_ABDUCTION",
                    "SCAPULAR_ELEVATION",
                    "ELBOW_FLEXION",
                    "ELBOW_EXTENSION",
                    "CORE");
            case HYPERTROPHY -> List.of(
                    "HORIZONTAL_PUSH",
                    "HORIZONTAL_PULL",
                    "VERTICAL_PUSH",
                    "VERTICAL_PULL",
                    "SQUAT",
                    "HINGE",
                    "SHOULDER_ABDUCTION",
                    "SHOULDER_HORIZONTAL_ABDUCTION",
                    "SCAPULAR_ELEVATION",
                    "ELBOW_FLEXION",
                    "ELBOW_EXTENSION",
                    "CALF_RAISE",
                    "CORE");
            case GENERAL_FITNESS -> List.of(
                    "CORE",
                    "SQUAT",
                    "HINGE",
                    "HORIZONTAL_PULL",
                    "HORIZONTAL_PUSH",
                    "VERTICAL_PULL",
                    "VERTICAL_PUSH",
                    "CALF_RAISE",
                    "SHOULDER_ABDUCTION",
                    "SHOULDER_HORIZONTAL_ABDUCTION",
                    "SCAPULAR_ELEVATION",
                    "ELBOW_FLEXION",
                    "ELBOW_EXTENSION");
        };
        int index = priorities.indexOf(pattern);
        return index < 0 ? priorities.size() : index;
    }

    private static void register(
            Exercise exercise,
            PlanValidationEngine.ExerciseFacts facts,
            Set<String> usedCodes,
            Map<String, Integer> movementCounts,
            Map<String, Integer> muscleSets,
            Map<String, Integer> globalUsage) {
        usedCodes.add(exercise.exerciseCode());
        movementCounts.merge(facts.movementPattern(), 1, Integer::sum);
        facts.primaryMuscles().forEach(muscle -> muscleSets.merge(muscle, exercise.workSets(), Integer::sum));
        globalUsage.merge(exercise.exerciseCode(), 1, Integer::sum);
    }

    private static int estimatedSeconds(
            List<Exercise> exercises, Exercise nextExercise, PlanRulePolicy policy) {
        boolean loadedExercisePresent = nextExercise.weightStatus() != WeightStatus.BODYWEIGHT
                || exercises.stream().anyMatch(exercise -> exercise.weightStatus() != WeightStatus.BODYWEIGHT);
        return policy.duration().sessionWarmupSeconds(loadedExercisePresent)
                + exercises.stream().mapToInt(exercise -> estimatedSeconds(exercise, policy)).sum()
                + estimatedSeconds(nextExercise, policy);
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
            days.add(new Day(day.code(), day.name(), exercises, day.focus()));
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
                "1.6.0",
                new PlanRulePolicy.PlanLimits(2, 6, 8, 90),
                new PlanRulePolicy.Prescription(2, 4, 5, 15),
                new PlanRulePolicy.Rest(45, 240),
                new PlanRulePolicy.Duration(45, 75),
                new PlanRulePolicy.Balance(1, 12, 48));
        private static final PlanRulePolicy LEGACY_POLICY = new PlanRulePolicy(
                "1.6.0",
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

    public record Day(String code, String name, List<Exercise> exercises, SessionFocus focus) {
        public Day {
            requireText(code, "day code");
            requireText(name, "day name");
            exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
            Objects.requireNonNull(focus, "session focus must not be null");
        }

        public Day(String code, String name, List<Exercise> exercises) {
            this(code, name, exercises, SessionFocus.infer(code, name));
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
    public enum TrainingSplit { UPPER_LOWER, PUSH_PULL_LEGS, BODY_PART_FIVE_DAY }

    public enum SessionFocus {
        FULL_BODY(Set.of(), Set.of()),
        UPPER(
                Set.of("HORIZONTAL_PUSH", "VERTICAL_PUSH", "HORIZONTAL_PULL", "VERTICAL_PULL",
                        "SHOULDER_ABDUCTION", "SHOULDER_HORIZONTAL_ABDUCTION", "SCAPULAR_ELEVATION",
                        "ELBOW_FLEXION", "ELBOW_EXTENSION"),
                Set.of("HORIZONTAL_PUSH", "HORIZONTAL_PULL", "ELBOW_FLEXION", "ELBOW_EXTENSION")),
        LOWER(
                Set.of("SQUAT", "HINGE", "CALF_RAISE", "CORE"),
                Set.of("SQUAT", "HINGE")),
        PUSH(
                Set.of("HORIZONTAL_PUSH", "VERTICAL_PUSH", "SHOULDER_ABDUCTION", "ELBOW_EXTENSION"),
                Set.of("HORIZONTAL_PUSH", "VERTICAL_PUSH", "ELBOW_EXTENSION")),
        PULL(
                Set.of("HORIZONTAL_PULL", "VERTICAL_PULL", "SHOULDER_HORIZONTAL_ABDUCTION",
                        "SCAPULAR_ELEVATION", "ELBOW_FLEXION"),
                Set.of("HORIZONTAL_PULL", "VERTICAL_PULL", "ELBOW_FLEXION")),
        CHEST(
                Set.of("HORIZONTAL_PUSH", "ELBOW_EXTENSION", "CORE"),
                Set.of("HORIZONTAL_PUSH")),
        BACK(
                Set.of("HORIZONTAL_PULL", "VERTICAL_PULL", "SHOULDER_HORIZONTAL_ABDUCTION",
                        "SCAPULAR_ELEVATION", "ELBOW_FLEXION"),
                Set.of("HORIZONTAL_PULL", "VERTICAL_PULL")),
        ARMS(
                Set.of("ELBOW_FLEXION", "ELBOW_EXTENSION"),
                Set.of("ELBOW_FLEXION", "ELBOW_EXTENSION")),
        SHOULDERS(
                Set.of("VERTICAL_PUSH", "SHOULDER_ABDUCTION", "SHOULDER_HORIZONTAL_ABDUCTION",
                        "SCAPULAR_ELEVATION"),
                Set.of("VERTICAL_PUSH", "SHOULDER_ABDUCTION"));

        private final Set<String> allowedPatterns;
        private final Set<String> requiredPatterns;

        SessionFocus(Set<String> allowedPatterns, Set<String> requiredPatterns) {
            this.allowedPatterns = Set.copyOf(allowedPatterns);
            this.requiredPatterns = Set.copyOf(requiredPatterns);
        }

        public boolean allows(String movementPattern) {
            return this == FULL_BODY || allowedPatterns.contains(movementPattern);
        }

        public Set<String> requiredPatterns() {
            return requiredPatterns;
        }

        public int maximumPatternOccurrences(String movementPattern, int configuredMaximum) {
            if (this == CHEST && "HORIZONTAL_PUSH".equals(movementPattern)) return 2;
            if (this == ARMS && ("ELBOW_FLEXION".equals(movementPattern)
                    || "ELBOW_EXTENSION".equals(movementPattern))) return 2;
            return configuredMaximum;
        }

        public static SessionFocus infer(String code, String name) {
            String normalizedCode = code == null ? "" : code.toUpperCase(java.util.Locale.ROOT);
            String normalizedName = name == null ? "" : name;
            if (normalizedCode.contains("CHEST") || normalizedName.contains("胸部")) return CHEST;
            if (normalizedCode.contains("BACK") || normalizedName.contains("背部重点")) return BACK;
            if (normalizedCode.contains("ARM") || normalizedName.contains("手臂")) return ARMS;
            if (normalizedCode.contains("SHOULDER") || normalizedName.contains("肩部")) return SHOULDERS;
            if (normalizedCode.contains("PUSH") || normalizedName.contains("推")) return PUSH;
            if (normalizedCode.contains("PULL") || normalizedName.contains("拉")
                    || normalizedName.contains("背部")) return PULL;
            if (normalizedCode.contains("LOWER") || normalizedCode.contains("LEG")
                    || normalizedName.contains("下肢") || normalizedName.contains("腿")) return LOWER;
            if (normalizedCode.contains("UPPER") || normalizedName.contains("上肢")) return UPPER;
            return FULL_BODY;
        }

        public static SessionFocus forWeeklyIndex(int sessionsPerWeek, int dayIndex) {
            return switch (sessionsPerWeek) {
                case 4 -> dayIndex % 2 == 0 ? LOWER : UPPER;
                case 5 -> switch (dayIndex) {
                    case 0, 3 -> PUSH;
                    case 1, 4 -> PULL;
                    default -> LOWER;
                };
                case 6 -> switch (dayIndex % 3) {
                    case 0 -> PUSH;
                    case 1 -> PULL;
                    default -> LOWER;
                };
                default -> FULL_BODY;
            };
        }

        public static SessionFocus forSplitIndex(TrainingSplit split, int sessionsPerWeek, int dayIndex) {
            Objects.requireNonNull(split, "training split must not be null");
            return switch (split) {
                case UPPER_LOWER -> dayIndex % 2 == 0 ? UPPER : LOWER;
                case PUSH_PULL_LEGS -> switch (dayIndex % 3) {
                    case 0 -> PUSH;
                    case 1 -> PULL;
                    default -> LOWER;
                };
                case BODY_PART_FIVE_DAY -> switch (dayIndex) {
                    case 0 -> CHEST;
                    case 1 -> BACK;
                    case 2 -> LOWER;
                    case 3 -> ARMS;
                    case 4 -> SHOULDERS;
                    default -> throw new IllegalArgumentException("five-day split index is invalid");
                };
            };
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
