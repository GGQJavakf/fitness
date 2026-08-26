package com.aifitness.assistant.plan.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.HashSet;
import java.util.Set;

public record PlanDraft(
        String templateCode,
        TrainingSplit trainingSplit,
        String name,
        List<Day> days,
        Map<String, FieldLock.Status> locks,
        String presetCode,
        String presetVersion,
        List<String> executionRules,
        List<String> progressionRules) {

    public PlanDraft(
            String templateCode,
            TrainingSplit trainingSplit,
            String name,
            List<Day> days,
            Map<String, FieldLock.Status> locks) {
        this(templateCode, trainingSplit, name, days, locks, null, null, List.of(), List.of());
    }

    public PlanDraft(
            String templateCode,
            String name,
            List<Day> days,
            Map<String, FieldLock.Status> locks) {
        this(templateCode, inferTrainingSplit(templateCode), name, days, locks, null, null, List.of(), List.of());
    }

    public PlanDraft {
        templateCode = requireText(templateCode, "templateCode");
        name = requireText(name, "name");
        days = List.copyOf(Objects.requireNonNull(days, "days must not be null"));
        if (days.isEmpty() || days.size() > 6) {
            throw new IllegalArgumentException("plan must contain between 1 and 6 days");
        }
        Set<String> dayCodes = new HashSet<>();
        for (Day day : days) {
            if (!dayCodes.add(day.code())) {
                throw new IllegalArgumentException("day codes must be unique");
            }
            Set<String> exerciseCodes = new HashSet<>();
            if (day.exercises().stream().anyMatch(exercise -> !exerciseCodes.add(exercise.exerciseCode()))) {
                throw new IllegalArgumentException("exercise codes must be unique within a day");
            }
        }
        locks = Map.copyOf(locks == null ? Map.of() : locks);
        if ((presetCode == null) != (presetVersion == null)) {
            throw new IllegalArgumentException("preset code and version must be provided together");
        }
        if (presetCode != null) {
            presetCode = requireStableCode(presetCode, "preset code");
            presetVersion = requireText(presetVersion, "preset version");
        }
        executionRules = immutableTexts(executionRules, "execution rule");
        progressionRules = immutableTexts(progressionRules, "progression rule");
        List<Day> validatedDays = days;
        locks.forEach((path, status) -> {
            new FieldLock(path, status, java.time.Instant.EPOCH);
            Path target = Path.parse(path);
            boolean exists = validatedDays.stream()
                    .filter(day -> day.code().equals(target.dayCode()))
                    .flatMap(day -> day.exercises().stream())
                    .anyMatch(exercise -> exercise.exerciseCode().equals(target.exerciseCode()));
            if (!exists) {
                throw new IllegalArgumentException("locked field target does not exist");
            }
            if (target.field().equals("targetWeightKg")) {
                boolean weightPresent = validatedDays.stream()
                        .filter(day -> day.code().equals(target.dayCode()))
                        .flatMap(day -> day.exercises().stream())
                        .filter(exercise -> exercise.exerciseCode().equals(target.exerciseCode()))
                        .findFirst()
                        .flatMap(Exercise::targetWeightKg)
                        .isPresent();
                if (!weightPresent) {
                    throw new IllegalArgumentException("locked weight must have a value");
                }
            }
        });
    }

    public Optional<Integer> valueAt(String fieldPath) {
        Path path = Path.parse(fieldPath);
        return days.stream()
                .filter(day -> day.code().equals(path.dayCode()))
                .flatMap(day -> day.exercises().stream())
                .filter(exercise -> exercise.exerciseCode().equals(path.exerciseCode()))
                .findFirst()
                .map(exercise -> exercise.value(path.field()));
    }

    public Optional<BigDecimal> weightAt(String fieldPath) {
        Path path = Path.parse(fieldPath);
        if (!path.field().equals("targetWeightKg")) {
            throw new IllegalArgumentException("field is not a weight field");
        }
        return days.stream()
                .filter(day -> day.code().equals(path.dayCode()))
                .flatMap(day -> day.exercises().stream())
                .filter(exercise -> exercise.exerciseCode().equals(path.exerciseCode()))
                .findFirst()
                .flatMap(Exercise::targetWeightKg);
    }

    public boolean isTargetWeightLocked(String exerciseCode) {
        return locks.entrySet().stream().anyMatch(entry -> {
            Path path = Path.parse(entry.getKey());
            return path.exerciseCode().equals(exerciseCode)
                    && path.field().equals("targetWeightKg")
                    && entry.getValue() != FieldLock.Status.UNLOCKED;
        });
    }

    public PlanDraft withTargetWeight(String exerciseCode, BigDecimal weightKg) {
        requireStableCode(exerciseCode, "exercise code");
        Objects.requireNonNull(weightKg, "target weight must not be null");
        if (weightKg.signum() < 0) {
            throw new IllegalArgumentException("target weight must not be negative");
        }
        boolean found = false;
        List<Day> updatedDays = new ArrayList<>(days.size());
        for (Day day : days) {
            List<Exercise> updatedExercises = new ArrayList<>(day.exercises().size());
            for (Exercise exercise : day.exercises()) {
                if (exercise.exerciseCode().equals(exerciseCode)) {
                    updatedExercises.add(exercise.withTargetWeight(weightKg));
                    found = true;
                } else {
                    updatedExercises.add(exercise);
                }
            }
            updatedDays.add(day.withExercises(updatedExercises));
        }
        if (!found) {
            throw new IllegalArgumentException("exercise does not exist in plan");
        }
        return copyWith(updatedDays, locks);
    }

    public PlanDraft preserveLockedValues(PlanDraft base, Map<String, FieldLock.Status> requestedLocks) {
        Objects.requireNonNull(base, "base plan must not be null");
        Map<String, FieldLock.Status> mergedLocks = new LinkedHashMap<>(base.locks());
        if (requestedLocks != null) {
            requestedLocks.forEach((path, status) -> {
                new FieldLock(path, status, java.time.Instant.EPOCH);
                FieldLock.Status existing = mergedLocks.get(path);
                if (existing == FieldLock.Status.RULE_LOCKED && status != FieldLock.Status.RULE_LOCKED) {
                    throw new IllegalArgumentException("rule locked field cannot be changed");
                }
                if (status == FieldLock.Status.RULE_LOCKED && existing != FieldLock.Status.RULE_LOCKED) {
                    throw new IllegalArgumentException("rule locks cannot be created by a plan edit");
                }
                if (status == FieldLock.Status.UNLOCKED) {
                    mergedLocks.remove(path);
                } else {
                    mergedLocks.put(path, status);
                }
            });
        }

        PlanDraft result = copyWith(days, mergedLocks);
        for (Map.Entry<String, FieldLock.Status> entry : base.locks().entrySet()) {
            FieldLock.Status requested = requestedLocks == null ? null : requestedLocks.get(entry.getKey());
            if (requested == FieldLock.Status.UNLOCKED) {
                continue;
            }
            Path path = Path.parse(entry.getKey());
            if (path.field().equals("targetWeightKg")) {
                BigDecimal lockedWeight = base.weightAt(entry.getKey()).orElseThrow(
                        () -> new IllegalArgumentException("locked weight does not exist in base plan"));
                result = result.withWeightValue(entry.getKey(), lockedWeight);
            } else {
                Integer lockedValue = base.valueAt(entry.getKey()).orElseThrow(
                        () -> new IllegalArgumentException("locked field does not exist in base plan"));
                result = result.withValue(entry.getKey(), lockedValue);
            }
        }
        return result;
    }

    private PlanDraft withWeightValue(String fieldPath, BigDecimal value) {
        Path path = Path.parse(fieldPath);
        boolean found = false;
        List<Day> updatedDays = new ArrayList<>(days.size());
        for (Day day : days) {
            List<Exercise> updatedExercises = new ArrayList<>(day.exercises().size());
            for (Exercise exercise : day.exercises()) {
                if (day.code().equals(path.dayCode()) && exercise.exerciseCode().equals(path.exerciseCode())) {
                    updatedExercises.add(exercise.withTargetWeight(value));
                    found = true;
                } else {
                    updatedExercises.add(exercise);
                }
            }
            updatedDays.add(day.withExercises(updatedExercises));
        }
        if (!found) {
            throw new IllegalArgumentException("locked weight does not exist in candidate plan");
        }
        return copyWith(updatedDays, locks);
    }

    private PlanDraft withValue(String fieldPath, int value) {
        Path path = Path.parse(fieldPath);
        boolean found = false;
        List<Day> updatedDays = new ArrayList<>(days.size());
        for (Day day : days) {
            if (!day.code().equals(path.dayCode())) {
                updatedDays.add(day);
                continue;
            }
            List<Exercise> updatedExercises = new ArrayList<>(day.exercises().size());
            for (Exercise exercise : day.exercises()) {
                if (exercise.exerciseCode().equals(path.exerciseCode())) {
                    updatedExercises.add(exercise.withValue(path.field(), value));
                    found = true;
                } else {
                    updatedExercises.add(exercise);
                }
            }
            updatedDays.add(day.withExercises(updatedExercises));
        }
        if (!found) {
            throw new IllegalArgumentException("locked field does not exist in candidate plan");
        }
        return copyWith(updatedDays, locks);
    }

    private PlanDraft copyWith(List<Day> updatedDays, Map<String, FieldLock.Status> updatedLocks) {
        return new PlanDraft(
                templateCode, trainingSplit, name, updatedDays, updatedLocks,
                presetCode, presetVersion, executionRules, progressionRules);
    }

    public record Day(
            String code,
            String name,
            List<Exercise> exercises,
            String weekday,
            String focus,
            int estimatedMinutesMin,
            int estimatedMinutesMax,
            List<WarmupStep> warmup,
            List<String> notes) {

        public Day(String code, String name, List<Exercise> exercises) {
            this(code, name, exercises, null, null, 0, 0, List.of(), List.of());
        }

        public Day {
            code = requireStableCode(code, "day code");
            name = requireText(name, "day name");
            exercises = List.copyOf(Objects.requireNonNull(exercises, "exercises must not be null"));
            if (exercises.isEmpty() || exercises.size() > 8) {
                throw new IllegalArgumentException("day must contain between 1 and 8 exercises");
            }
            if (weekday != null) weekday = requireStableCode(weekday, "weekday");
            if (focus != null) focus = requireText(focus, "focus");
            if (estimatedMinutesMin < 0 || estimatedMinutesMax < 0
                    || (estimatedMinutesMax > 0 && estimatedMinutesMin > estimatedMinutesMax)) {
                throw new IllegalArgumentException("estimated minute range is invalid");
            }
            warmup = List.copyOf(warmup == null ? List.of() : warmup);
            notes = immutableTexts(notes, "day note");
        }

        Day withExercises(List<Exercise> updatedExercises) {
            return new Day(
                    code, name, updatedExercises, weekday, focus,
                    estimatedMinutesMin, estimatedMinutesMax, warmup, notes);
        }
    }

    public record Exercise(
            String exerciseCode,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            WeightStatus weightStatus,
            Optional<BigDecimal> targetWeightKg,
            Integer targetRirMin,
            Integer targetRirMax,
            Integer eccentricSeconds,
            boolean perSide,
            String executionGroup,
            int executionOrder,
            OptionalSetRule optionalSetRule,
            List<String> notes) {
        public Exercise(
                String exerciseCode,
                int workSets,
                int repMin,
                int repMax,
                int restSeconds,
                WeightStatus weightStatus) {
            this(exerciseCode, workSets, repMin, repMax, restSeconds, weightStatus, Optional.empty(),
                    null, null, null, false, null, 0, null, List.of());
        }

        public Exercise(
                String exerciseCode,
                int workSets,
                int repMin,
                int repMax,
                int restSeconds,
                WeightStatus weightStatus,
                Optional<BigDecimal> targetWeightKg) {
            this(exerciseCode, workSets, repMin, repMax, restSeconds, weightStatus, targetWeightKg,
                    null, null, null, false, null, 0, null, List.of());
        }

        public Exercise {
            exerciseCode = requireStableCode(exerciseCode, "exercise code");
            if (workSets < 0 || repMin < 0 || repMax < 0 || restSeconds < 0) {
                throw new IllegalArgumentException("exercise prescription contains invalid numbers");
            }
            Objects.requireNonNull(weightStatus, "weightStatus must not be null");
            targetWeightKg = Objects.requireNonNull(targetWeightKg, "target weight must not be null")
                    .map(BigDecimal::stripTrailingZeros);
            if (targetWeightKg.filter(weight -> weight.signum() < 0).isPresent()) {
                throw new IllegalArgumentException("target weight must not be negative");
            }
            if (weightStatus == WeightStatus.BODYWEIGHT && targetWeightKg.isPresent()) {
                throw new IllegalArgumentException("bodyweight exercise cannot have a target weight");
            }
            if ((targetRirMin == null) != (targetRirMax == null)
                    || targetRirMin != null
                    && (targetRirMin < 0 || targetRirMax < targetRirMin || targetRirMax > 10)) {
                throw new IllegalArgumentException("target RIR range is invalid");
            }
            if (eccentricSeconds != null && (eccentricSeconds < 1 || eccentricSeconds > 10)) {
                throw new IllegalArgumentException("eccentric seconds must be between 1 and 10");
            }
            if ((executionGroup == null) != (executionOrder == 0)) {
                throw new IllegalArgumentException("execution group and order must be provided together");
            }
            if (executionGroup != null) {
                executionGroup = requireStableCode(executionGroup, "execution group");
                if (executionOrder < 1 || executionOrder > 8) {
                    throw new IllegalArgumentException("execution order is invalid");
                }
            }
            notes = immutableTexts(notes, "exercise note");
        }

        int value(String field) {
            return switch (field) {
                case "workSets" -> workSets;
                case "repMin" -> repMin;
                case "repMax" -> repMax;
                case "restSeconds" -> restSeconds;
                default -> throw new IllegalArgumentException("field is not lockable");
            };
        }

        Exercise withValue(String field, int value) {
            return switch (field) {
                case "workSets" -> new Exercise(
                        exerciseCode, value, repMin, repMax, restSeconds, weightStatus, targetWeightKg,
                        targetRirMin, targetRirMax, eccentricSeconds, perSide,
                        executionGroup, executionOrder, optionalSetRule, notes);
                case "repMin" -> new Exercise(
                        exerciseCode, workSets, value, repMax, restSeconds, weightStatus, targetWeightKg,
                        targetRirMin, targetRirMax, eccentricSeconds, perSide,
                        executionGroup, executionOrder, optionalSetRule, notes);
                case "repMax" -> new Exercise(
                        exerciseCode, workSets, repMin, value, restSeconds, weightStatus, targetWeightKg,
                        targetRirMin, targetRirMax, eccentricSeconds, perSide,
                        executionGroup, executionOrder, optionalSetRule, notes);
                case "restSeconds" -> new Exercise(
                        exerciseCode, workSets, repMin, repMax, value, weightStatus, targetWeightKg,
                        targetRirMin, targetRirMax, eccentricSeconds, perSide,
                        executionGroup, executionOrder, optionalSetRule, notes);
                default -> throw new IllegalArgumentException("field is not lockable");
            };
        }

        Exercise withTargetWeight(BigDecimal value) {
            return new Exercise(
                    exerciseCode, workSets, repMin, repMax, restSeconds, WeightStatus.KNOWN, Optional.of(value),
                    targetRirMin, targetRirMax, eccentricSeconds, perSide,
                    executionGroup, executionOrder, optionalSetRule, notes);
        }
    }

    public record WarmupStep(String instruction, String prescription, boolean optional) {
        public WarmupStep {
            instruction = requireText(instruction, "warmup instruction");
            if (prescription != null) prescription = requireText(prescription, "warmup prescription");
        }
    }

    public record OptionalSetRule(String conditionCode, String exclusiveChoiceGroup, int additionalSets) {
        public OptionalSetRule {
            conditionCode = requireStableCode(conditionCode, "optional set condition");
            exclusiveChoiceGroup = requireStableCode(exclusiveChoiceGroup, "exclusive choice group");
            if (additionalSets != 1) {
                throw new IllegalArgumentException("optional set rule must add exactly one set");
            }
        }
    }

    public enum WeightStatus {
        KNOWN,
        NEEDS_CALIBRATION,
        BODYWEIGHT
    }

    public enum TrainingSplit {
        UPPER_LOWER,
        PUSH_PULL_LEGS,
        BODY_PART_FIVE_DAY
    }

    public static TrainingSplit inferTrainingSplit(String templateCode) {
        if (templateCode == null) return null;
        if (templateCode.startsWith("UPPER_LOWER_")) return TrainingSplit.UPPER_LOWER;
        if (templateCode.startsWith("PUSH_PULL_LEGS_")) return TrainingSplit.PUSH_PULL_LEGS;
        if (templateCode.startsWith("BODY_PART_")) return TrainingSplit.BODY_PART_FIVE_DAY;
        return null;
    }

    private record Path(String dayCode, String exerciseCode, String field) {
        static Path parse(String value) {
            new FieldLock(value, FieldLock.Status.USER_LOCKED, java.time.Instant.EPOCH);
            String[] parts = value.split("/", -1);
            return new Path(parts[2], parts[4], parts[5]);
        }
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static String requireStableCode(String value, String label) {
        value = requireText(value, label);
        if (value.contains("/")) {
            throw new IllegalArgumentException(label + " must not contain slash");
        }
        return value;
    }

    private static List<String> immutableTexts(List<String> values, String label) {
        List<String> safe = List.copyOf(values == null ? List.of() : values);
        safe.forEach(value -> requireText(value, label));
        return safe;
    }
}
