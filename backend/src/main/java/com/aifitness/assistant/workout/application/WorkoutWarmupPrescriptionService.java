package com.aifitness.assistant.workout.application;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.profile.domain.EquipmentProfile;
import com.aifitness.assistant.rules.domain.WorkoutWarmupPrescriptionEngine;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Resolves catalog and user-equipment facts needed by the deterministic warm-up rules engine. */
public final class WorkoutWarmupPrescriptionService {

    private final ExerciseQueryService exercises;
    private final ProfileService profiles;
    private final WorkoutWarmupPrescriptionEngine engine;

    public WorkoutWarmupPrescriptionService(
            ExerciseQueryService exercises,
            ProfileService profiles,
            WorkoutWarmupPrescriptionEngine engine) {
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
        this.engine = Objects.requireNonNull(engine, "warmup engine must not be null");
    }

    public WorkoutWarmupPrescriptionEngine.Prescription prescribe(
            AuthenticatedUserId user, List<PlanWorkoutSnapshotQuery.ExerciseSource> sourceExercises) {
        Objects.requireNonNull(user, "user must not be null");
        Objects.requireNonNull(sourceExercises, "source exercises must not be null");
        Map<String, ExerciseCatalog.Exercise> catalogByCode = exercises.catalog().stream()
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, Function.identity()));
        List<WorkoutWarmupPrescriptionEngine.ExerciseInput> inputs = sourceExercises.stream()
                .map(source -> new WorkoutWarmupPrescriptionEngine.ExerciseInput(
                        source.order(),
                        source.exerciseCode(),
                        java.util.Optional.ofNullable(catalogByCode.get(source.exerciseCode()))
                                .map(ExerciseCatalog.Exercise::movementPattern)
                                .orElse("UNKNOWN"),
                        source.weightStatus(),
                        source.targetWeightKg(),
                        source.equipment()))
                .toList();
        EquipmentLevelContext equipment = availableLevels(user);
        return engine.prescribe(inputs, equipment.levelsByType(), equipment.ambiguousTypes());
    }

    private EquipmentLevelContext availableLevels(AuthenticatedUserId user) {
        List<EquipmentProfile.Item> items;
        try {
            items = profiles.getEquipment(user).items();
        } catch (ProfileService.ProfileNotFoundException exception) {
            return new EquipmentLevelContext(Map.of(), java.util.Set.of());
        }
        Map<String, List<EquipmentProfile.Item>> itemsByType = items.stream()
                .collect(Collectors.groupingBy(
                        EquipmentProfile.Item::equipmentType,
                        LinkedHashMap::new,
                        Collectors.toList()));
        java.util.Set<String> ambiguousTypes = itemsByType.entrySet().stream()
                .filter(entry -> entry.getValue().size() != 1)
                .map(Map.Entry::getKey)
                .collect(Collectors.toUnmodifiableSet());
        Map<String, List<BigDecimal>> levels = itemsByType.entrySet().stream()
                .filter(entry -> entry.getValue().size() == 1)
                .collect(Collectors.toUnmodifiableMap(
                        Map.Entry::getKey,
                        entry -> entry.getValue().getFirst().availableLevels()));
        return new EquipmentLevelContext(levels, ambiguousTypes);
    }

    private record EquipmentLevelContext(
            Map<String, List<BigDecimal>> levelsByType,
            java.util.Set<String> ambiguousTypes) {}
}
