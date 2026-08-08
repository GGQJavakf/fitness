package com.aifitness.assistant.content.application;

import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.ReleaseStatus;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

public final class ExerciseQueryService {

    private final ContentCatalogRepository catalogs;
    private final UserEquipmentProvider equipment;
    private final ContentEnvironment environment;

    public ExerciseQueryService(
            ContentCatalogRepository catalogs,
            UserEquipmentProvider equipment,
            ContentEnvironment environment) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs must not be null");
        this.equipment = Objects.requireNonNull(equipment, "equipment must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    public List<ExerciseCatalog.Exercise> list(AuthenticatedUserId user, Filter filter) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(filter, "filter must not be null");
        ExerciseCatalog catalog = catalogs.exercises();
        if (!catalog.metadata().isEligibleFor(environment)) {
            return List.of();
        }
        Set<String> availableEquipment = Set.copyOf(equipment.availableEquipment(user.value()));
        Map<String, ExerciseCatalog.Exercise> eligibleByCode = catalog.exercises().stream()
                .filter(ExerciseCatalog.Exercise::active)
                .filter(ExerciseCatalog.Exercise::hasValidRights)
                .filter(exercise -> exercise.supports(availableEquipment))
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, exercise -> exercise));
        return eligibleByCode.values().stream()
                .filter(filter.predicate())
                .map(exercise -> exercise.withAlternatives(exercise.alternatives().stream()
                        .filter(alternative -> eligibleByCode.containsKey(alternative.exerciseCode()))
                        .filter(alternative -> eligibleAlternative(alternative.reviewStatus()))
                        .sorted(Comparator.comparingInt(ExerciseCatalog.Alternative::rank))
                        .toList()))
                .sorted(Comparator.comparing(ExerciseCatalog.Exercise::code))
                .toList();
    }

    public Optional<ExerciseCatalog.Exercise> get(AuthenticatedUserId user, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("exercise id is required");
        }
        return list(user, Filter.none()).stream()
                .filter(exercise -> exercise.code().equals(code) || exercise.stableId().toString().equals(code))
                .findFirst();
    }

    public List<ExerciseCatalog.Exercise> catalog() {
        ExerciseCatalog catalog = catalogs.exercises();
        if (!catalog.metadata().isEligibleFor(environment)) {
            return List.of();
        }
        Set<String> releasedCodes = catalog.exercises().stream()
                .filter(ExerciseCatalog.Exercise::active)
                .filter(ExerciseCatalog.Exercise::hasValidRights)
                .map(ExerciseCatalog.Exercise::code)
                .collect(Collectors.toUnmodifiableSet());
        return catalog.exercises().stream()
                .filter(ExerciseCatalog.Exercise::active)
                .filter(ExerciseCatalog.Exercise::hasValidRights)
                .map(exercise -> exercise.withAlternatives(exercise.alternatives().stream()
                        .filter(alternative -> releasedCodes.contains(alternative.exerciseCode()))
                        .filter(alternative -> eligibleAlternative(alternative.reviewStatus()))
                        .sorted(Comparator.comparingInt(ExerciseCatalog.Alternative::rank))
                        .toList()))
                .sorted(Comparator.comparing(ExerciseCatalog.Exercise::code))
                .toList();
    }

    public String version() {
        return catalogs.exercises().metadata().version();
    }

    private boolean eligibleAlternative(ReleaseStatus status) {
        if (environment == ContentEnvironment.PUBLIC) {
            return status == ReleaseStatus.PUBLIC_RELEASE_APPROVED;
        }
        return status == ReleaseStatus.AI_VALIDATED || status == ReleaseStatus.PUBLIC_RELEASE_APPROVED;
    }

    public record Filter(Optional<String> equipmentType, Optional<String> movementPattern, Optional<String> muscleGroup) {
        public Filter {
            equipmentType = normalized(equipmentType);
            movementPattern = normalized(movementPattern);
            muscleGroup = normalized(muscleGroup);
        }

        public static Filter none() {
            return new Filter(Optional.empty(), Optional.empty(), Optional.empty());
        }

        private Predicate<ExerciseCatalog.Exercise> predicate() {
            return exercise -> equipmentType.map(exercise.equipment()::contains).orElse(true)
                    && movementPattern.map(exercise.movementPattern()::equals).orElse(true)
                    && muscleGroup.map(exercise.primaryMuscles()::contains).orElse(true);
        }

        private static Optional<String> normalized(Optional<String> value) {
            return Objects.requireNonNull(value, "filter value must not be null")
                    .map(String::trim)
                    .filter(text -> !text.isEmpty())
                    .map(String::toUpperCase);
        }
    }
}
