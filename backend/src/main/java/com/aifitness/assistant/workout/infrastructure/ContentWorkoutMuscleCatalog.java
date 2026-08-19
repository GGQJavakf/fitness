package com.aifitness.assistant.workout.infrastructure;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.workout.application.WorkoutMuscleCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

/** Adapts the released content catalog without applying user-equipment filtering. */
public final class ContentWorkoutMuscleCatalog implements WorkoutMuscleCatalog {
    private static final String LEGACY_1_6_PATH = "rule-config/archive/exercises-v1.6.0.json";

    private final ExerciseQueryService exercises;
    private final Map<String, Map<String, Set<String>>> archivedPrimaryMuscles;

    public ContentWorkoutMuscleCatalog(ExerciseQueryService exercises, ObjectMapper objectMapper) {
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.archivedPrimaryMuscles = Map.of(
                "1.6.0", loadPrimaryMuscles(
                        Objects.requireNonNull(objectMapper, "object mapper must not be null"),
                        LEGACY_1_6_PATH));
    }

    @Override
    public Optional<Set<String>> primaryMuscles(String exerciseCode, String contentVersion) {
        if (exerciseCode == null || exerciseCode.isBlank()) {
            throw new IllegalArgumentException("exercise code must not be blank");
        }
        if (contentVersion == null || contentVersion.isBlank()) {
            throw new IllegalArgumentException("content version must not be blank");
        }
        String normalizedVersion = contentVersion.trim();
        if (!exercises.version().equals(normalizedVersion)) {
            return Optional.ofNullable(archivedPrimaryMuscles.get(normalizedVersion))
                    .map(version -> version.get(exerciseCode.trim()));
        }
        return exercises.catalog().stream()
                .filter(exercise -> exercise.code().equals(exerciseCode.trim()))
                .findFirst()
                .map(exercise -> Set.copyOf(exercise.primaryMuscles()));
    }

    private static Map<String, Set<String>> loadPrimaryMuscles(ObjectMapper objectMapper, String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            JsonNode document = objectMapper.readTree(input);
            Map<String, Set<String>> values = new HashMap<>();
            document.path("exercises").forEach(exercise -> {
                Set<String> muscles = new java.util.HashSet<>();
                exercise.path("primaryMuscles").forEach(muscle -> muscles.add(muscle.asText()));
                values.put(exercise.path("code").asText(), Set.copyOf(muscles));
            });
            return Map.copyOf(values);
        } catch (IOException exception) {
            throw new IllegalStateException("archived exercise catalog cannot be loaded: " + path, exception);
        }
    }
}
