package com.aifitness.assistant.content.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public record PlanTemplateCatalog(ReleaseMetadata metadata, String contentVersion, List<Template> templates) {

    public PlanTemplateCatalog {
        Objects.requireNonNull(metadata, "metadata must not be null");
        if (contentVersion == null || contentVersion.isBlank()) {
            throw new IllegalArgumentException("template content version is required");
        }
        templates = List.copyOf(Objects.requireNonNull(templates, "templates must not be null"));
    }

    public record Template(
            String code, String name, int sessionsPerWeek, Set<String> exerciseCodes, List<Day> days) {
        public Template {
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("template code and name are required");
            }
            if (sessionsPerWeek < 2 || sessionsPerWeek > 6) {
                throw new IllegalArgumentException("template frequency must be between 2 and 6");
            }
            exerciseCodes = Set.copyOf(
                    Objects.requireNonNull(exerciseCodes, "exercise codes must not be null"));
            if (exerciseCodes.isEmpty()) {
                throw new IllegalArgumentException("template must reference exercises");
            }
            days = List.copyOf(Objects.requireNonNull(days, "template days must not be null"));
            if (!days.isEmpty() && days.size() != sessionsPerWeek) {
                throw new IllegalArgumentException("template days must match weekly frequency");
            }
        }

        public Template(String code, String name, int sessionsPerWeek, Set<String> exerciseCodes) {
            this(code, name, sessionsPerWeek, exerciseCodes, List.of());
        }
    }

    public record Day(String code, String name, List<ExerciseSlot> exercises) {
        public Day {
            if (code == null || code.isBlank() || name == null || name.isBlank()) {
                throw new IllegalArgumentException("day code and name are required");
            }
            exercises = List.copyOf(Objects.requireNonNull(exercises, "day exercises must not be null"));
            if (exercises.isEmpty()) {
                throw new IllegalArgumentException("day must contain exercises");
            }
        }
    }

    public record ExerciseSlot(
            String exerciseCode,
            int order,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            String initialWeightState) {
        public ExerciseSlot {
            if (exerciseCode == null || exerciseCode.isBlank() || order < 1
                    || initialWeightState == null || initialWeightState.isBlank()) {
                throw new IllegalArgumentException("template exercise slot is invalid");
            }
        }
    }
}
