package com.aifitness.assistant.rules.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Builds the immutable, server-authoritative warm-up prescription stored with a workout. */
public final class WorkoutWarmupPrescriptionEngine {

    public static final String SCHEMA_VERSION = "workout-warmup-prescription-v1";

    private static final Set<String> NON_LOAD_BEARING_EQUIPMENT = Set.of("BODYWEIGHT", "BENCH");

    private final PlanRulePolicy policy;

    public WorkoutWarmupPrescriptionEngine(PlanRulePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy must not be null");
    }

    public Prescription prescribe(
            List<ExerciseInput> exercises, Map<String, List<BigDecimal>> availableLevelsByEquipmentType) {
        return prescribe(exercises, availableLevelsByEquipmentType, Set.of());
    }

    public Prescription prescribe(
            List<ExerciseInput> exercises,
            Map<String, List<BigDecimal>> availableLevelsByEquipmentType,
            Set<String> ambiguousEquipmentTypes) {
        Objects.requireNonNull(exercises, "exercises must not be null");
        Objects.requireNonNull(availableLevelsByEquipmentType, "available equipment levels must not be null");
        Objects.requireNonNull(ambiguousEquipmentTypes, "ambiguous equipment types must not be null");

        Optional<ExerciseInput> rampExercise = exercises.stream()
                .sorted(Comparator.comparingInt(ExerciseInput::order))
                .filter(this::isLoadedCompoundExercise)
                .findFirst();

        Optional<RampWarmup> rampWarmup = rampExercise.map(exercise -> prescribeRamp(
                exercise,
                normalizeLevelsByEquipmentType(availableLevelsByEquipmentType),
                ambiguousEquipmentTypes.stream().map(WorkoutWarmupPrescriptionEngine::normalizeCode)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet())));
        return new Prescription(
                SCHEMA_VERSION,
                policy.version(),
                new GeneralWarmup(1, policy.duration().generalWarmupSeconds()),
                rampWarmup,
                policy.warmup().countsTowardTrainingVolume(),
                false);
    }

    private RampWarmup prescribeRamp(
            ExerciseInput exercise,
            Map<String, List<BigDecimal>> availableLevelsByEquipmentType,
            Set<String> ambiguousEquipmentTypes) {
        Set<String> loadBearingEquipment = new LinkedHashSet<>();
        exercise.equipment().stream()
                .map(WorkoutWarmupPrescriptionEngine::normalizeCode)
                .filter(code -> !NON_LOAD_BEARING_EQUIPMENT.contains(code))
                .forEach(loadBearingEquipment::add);

        if (!"KNOWN".equals(normalizeCode(exercise.weightStatus()))
                || exercise.targetWeight().filter(weight -> weight.signum() > 0).isEmpty()) {
            return calibrationRequired(
                    exercise,
                    loadBearingEquipment.stream().findFirst(),
                    "WORK_WEIGHT_NEEDS_CALIBRATION",
                    "请先校准该动作的正式重量，服务端不会推测热身重量。");
        }

        if (loadBearingEquipment.stream().anyMatch(ambiguousEquipmentTypes::contains)) {
            return calibrationRequired(
                    exercise,
                    Optional.empty(),
                    "EQUIPMENT_PROFILE_AMBIGUOUS",
                    "存在多个同类型器械档案，无法确定本次使用的器械，请先选择或校准具体器械。");
        }

        if (loadBearingEquipment.size() > 1) {
            return calibrationRequired(
                    exercise,
                    Optional.empty(),
                    "EQUIPMENT_LEVELS_AMBIGUOUS",
                    "该动作包含多个可加载器械，无法确定重量档位，请先校准后再开始递增热身。");
        }

        List<String> equipmentWithLevels = loadBearingEquipment.stream()
                .filter(type -> !availableLevelsByEquipmentType.getOrDefault(type, List.of()).isEmpty())
                .toList();
        if (equipmentWithLevels.isEmpty()) {
            return calibrationRequired(
                    exercise,
                    loadBearingEquipment.stream().findFirst(),
                    "EQUIPMENT_LEVELS_UNAVAILABLE",
                    "当前器械没有可用重量档位，请先校准器械后再开始递增热身。");
        }
        String equipmentType = equipmentWithLevels.getFirst();
        BigDecimal workWeight = exercise.targetWeight().orElseThrow();
        List<BigDecimal> levels = availableLevelsByEquipmentType.get(equipmentType).stream()
                .filter(level -> level != null && level.signum() > 0 && level.compareTo(workWeight) < 0)
                .distinct()
                .sorted()
                .toList();
        List<RampSet> rampSets = new ArrayList<>();
        List<BigDecimal> rampRatios = policy.warmup().knownWorkWeightRatios();
        List<Integer> rampReps = policy.warmup().rampSetReps();
        int requestedSetCount = Math.min(policy.duration().rampWarmupSetsPerSession(), rampRatios.size());
        for (int index = 0; index < requestedSetCount; index++) {
            BigDecimal ceiling = workWeight.multiply(rampRatios.get(index));
            Optional<BigDecimal> flooredLevel = levels.stream()
                    .filter(level -> level.compareTo(ceiling) <= 0)
                    .max(Comparator.naturalOrder());
            if (flooredLevel.isPresent()
                    && (rampSets.isEmpty()
                            || rampSets.getLast().weight().compareTo(flooredLevel.orElseThrow()) != 0)) {
                rampSets.add(new RampSet(flooredLevel.orElseThrow(), rampReps.get(index)));
            }
        }
        if (rampSets.isEmpty()) {
            return calibrationRequired(
                    exercise,
                    Optional.of(equipmentType),
                    "NO_USABLE_RAMP_LEVEL",
                    "可用器械档位无法生成低于正式重量的热身组，请先校准后再开始训练。");
        }
        return new RampWarmup(
                exercise.order(),
                RampStatus.READY,
                Optional.of(equipmentType),
                rampSets,
                Optional.empty(),
                Optional.empty());
    }

    private static RampWarmup calibrationRequired(
            ExerciseInput exercise,
            Optional<String> equipmentType,
            String code,
            String message) {
        return new RampWarmup(
                exercise.order(),
                RampStatus.CALIBRATION_REQUIRED,
                equipmentType,
                List.of(),
                Optional.of(code),
                Optional.of(message));
    }

    private boolean isLoadedCompoundExercise(ExerciseInput exercise) {
        if (!policy.warmup().eligibleLoadedCompoundMovementPatterns()
                .contains(normalizeCode(exercise.movementPattern()))) {
            return false;
        }
        return exercise.equipment().stream()
                .map(WorkoutWarmupPrescriptionEngine::normalizeCode)
                .anyMatch(code -> !NON_LOAD_BEARING_EQUIPMENT.contains(code));
    }

    private static Map<String, List<BigDecimal>> normalizeLevelsByEquipmentType(
            Map<String, List<BigDecimal>> levelsByEquipmentType) {
        java.util.LinkedHashMap<String, List<BigDecimal>> normalized = new java.util.LinkedHashMap<>();
        levelsByEquipmentType.forEach((type, levels) -> normalized.put(
                normalizeCode(type), levels == null ? List.of() : List.copyOf(levels)));
        return Map.copyOf(normalized);
    }

    private static String normalizeCode(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    }

    public record ExerciseInput(
            int order,
            String exerciseCode,
            String movementPattern,
            String weightStatus,
            Optional<BigDecimal> targetWeight,
            Set<String> equipment) {
        public ExerciseInput {
            if (order <= 0) {
                throw new IllegalArgumentException("exercise order must be positive");
            }
            exerciseCode = Objects.requireNonNull(exerciseCode, "exercise code must not be null");
            movementPattern = Objects.requireNonNull(movementPattern, "movement pattern must not be null");
            weightStatus = Objects.requireNonNull(weightStatus, "weight status must not be null");
            targetWeight = Objects.requireNonNull(targetWeight, "target weight must not be null");
            equipment = Set.copyOf(Objects.requireNonNull(equipment, "equipment must not be null"));
        }
    }

    public record Prescription(
            String schemaVersion,
            String ruleVersion,
            GeneralWarmup generalWarmup,
            Optional<RampWarmup> rampWarmup,
            boolean countsTowardTrainingVolume,
            boolean countsTowardProgression) {}

    public record GeneralWarmup(int occurrences, int durationSeconds) {}

    public record RampWarmup(
            int exerciseOrder,
            RampStatus status,
            Optional<String> equipmentType,
            List<RampSet> sets,
            Optional<String> calibrationCode,
            Optional<String> calibrationMessage) {
        public RampWarmup {
            equipmentType = Objects.requireNonNull(equipmentType, "equipment type must not be null");
            sets = List.copyOf(Objects.requireNonNull(sets, "ramp sets must not be null"));
            calibrationCode = Objects.requireNonNull(calibrationCode, "calibration code must not be null");
            calibrationMessage = Objects.requireNonNull(calibrationMessage, "calibration message must not be null");
        }
    }

    public record RampSet(BigDecimal weight, int reps) {
        public RampSet {
            Objects.requireNonNull(weight, "weight must not be null");
            if (weight.signum() <= 0 || reps <= 0) {
                throw new IllegalArgumentException("ramp set weight and repetitions must be positive");
            }
        }
    }

    public enum RampStatus {
        READY,
        CALIBRATION_REQUIRED
    }
}
