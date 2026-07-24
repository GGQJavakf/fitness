package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.profile.application.ProfileService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Supplies rule/template-prescribed actions for explicit user structure edits. */
public final class PlanExerciseOptionService {

    private final PlanVersionService plans;
    private final TemplateQueryService templates;
    private final ExerciseQueryService exercises;
    private final ProfileService profiles;

    public PlanExerciseOptionService(
            PlanVersionService plans,
            TemplateQueryService templates,
            ExerciseQueryService exercises,
            ProfileService profiles) {
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.templates = Objects.requireNonNull(templates, "templates must not be null");
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
        this.profiles = Objects.requireNonNull(profiles, "profiles must not be null");
    }

    public List<Option> list(AuthenticatedUserId user, UUID planId, String dayCode) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        if (dayCode == null || !dayCode.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException("dayCode must be a stable code");
        }
        TrainingPlan active = plans.getActive(user);
        if (!active.id().equals(planId)) throw new PlanVersionService.PlanNotFoundException();
        PlanDraft draft = active.activeVersion().plan();
        PlanDraft.Day targetDay = draft.days().stream()
                .filter(day -> day.code().equals(dayCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dayCode does not exist in the active plan"));
        Set<String> existingCodes = targetDay.exercises().stream()
                .map(PlanDraft.Exercise::exerciseCode)
                .collect(Collectors.toUnmodifiableSet());

        PlanTemplateCatalog.Template template = templates.list(user, Optional.empty()).stream()
                .filter(value -> value.code().equals(draft.templateCode()))
                .findFirst()
                .orElse(null);
        if (template == null) return List.of();

        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        Map<String, ExerciseCatalog.Exercise> eligible = exercises
                .list(user, ExerciseQueryService.Filter.none()).stream()
                .filter(exercise -> !excluded.contains(exercise.stableId()))
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, Function.identity()));
        Map<String, PlanTemplateCatalog.ExerciseSlot> firstPrescription = new LinkedHashMap<>();
        template.days().stream().flatMap(day -> day.exercises().stream())
                .forEach(slot -> firstPrescription.putIfAbsent(slot.exerciseCode(), slot));

        return firstPrescription.values().stream()
                .filter(slot -> !existingCodes.contains(slot.exerciseCode()))
                .filter(slot -> eligible.containsKey(slot.exerciseCode()))
                .map(slot -> toOption(slot, eligible.get(slot.exerciseCode())))
                .sorted(java.util.Comparator.comparing(Option::exerciseCode))
                .toList();
    }

    private static Option toOption(
            PlanTemplateCatalog.ExerciseSlot slot,
            ExerciseCatalog.Exercise exercise) {
        return new Option(
                slot.exerciseCode(), exercise.name(), slot.workSets(), slot.repMin(), slot.repMax(),
                slot.restSeconds(), PlanDraft.WeightStatus.valueOf(slot.initialWeightState()));
    }

    public record Option(
            String exerciseCode,
            String name,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            PlanDraft.WeightStatus weightStatus) {}
}
