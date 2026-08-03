package com.aifitness.assistant.content.application;

import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public final class TemplateQueryService {

    private final ContentCatalogRepository catalogs;
    private final ExerciseQueryService exercises;
    private final ContentEnvironment environment;

    public TemplateQueryService(
            ContentCatalogRepository catalogs,
            ExerciseQueryService exercises,
            ContentEnvironment environment) {
        this.catalogs = Objects.requireNonNull(catalogs, "catalogs must not be null");
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.environment = Objects.requireNonNull(environment, "environment must not be null");
    }

    public List<PlanTemplateCatalog.Template> list(
            AuthenticatedUserId user, Optional<Integer> weeklyFrequency) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Set<String> eligibleExerciseCodes = exercises.list(user, ExerciseQueryService.Filter.none()).stream()
                .map(ExerciseCatalog.Exercise::code)
                .collect(Collectors.toUnmodifiableSet());
        return activeTemplates(weeklyFrequency).stream()
                .filter(template -> eligibleExerciseCodes.containsAll(template.exerciseCodes()))
                .toList();
    }

    public List<PlanTemplateCatalog.Template> listForGeneration(Optional<Integer> weeklyFrequency) {
        return activeTemplates(weeklyFrequency);
    }

    public String version() {
        return catalogs.templates().metadata().version();
    }

    private List<PlanTemplateCatalog.Template> activeTemplates(Optional<Integer> weeklyFrequency) {
        weeklyFrequency = Objects.requireNonNull(weeklyFrequency, "weekly frequency must not be null");
        weeklyFrequency.ifPresent(frequency -> {
            if (frequency < 2 || frequency > 6) {
                throw new IllegalArgumentException("weeklyFrequency must be between 2 and 6");
            }
        });
        PlanTemplateCatalog catalog = catalogs.templates();
        if (!catalog.metadata().isEligibleFor(environment)
                || !catalog.contentVersion().equals(exercises.version())) {
            return List.of();
        }
        Optional<Integer> frequency = weeklyFrequency;
        return catalog.templates().stream()
                .filter(template -> frequency.map(value -> template.sessionsPerWeek() == value).orElse(true))
                .sorted(Comparator.comparing(PlanTemplateCatalog.Template::code))
                .toList();
    }
}
