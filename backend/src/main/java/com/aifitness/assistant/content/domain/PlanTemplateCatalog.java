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

    public record Template(String code, String name, int sessionsPerWeek, Set<String> exerciseCodes) {
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
        }
    }
}
