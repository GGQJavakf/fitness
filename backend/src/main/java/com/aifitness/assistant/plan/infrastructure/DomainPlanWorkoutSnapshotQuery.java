package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanVersionService;
import com.aifitness.assistant.plan.application.PlanWorkoutSnapshotQuery;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlanVersion;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Local/test snapshot query over public plan and content application capabilities. */
public final class DomainPlanWorkoutSnapshotQuery implements PlanWorkoutSnapshotQuery {
    private final PlanVersionService plans;
    private final ExerciseQueryService exercises;

    public DomainPlanWorkoutSnapshotQuery(PlanVersionService plans, ExerciseQueryService exercises) {
        this.plans = Objects.requireNonNull(plans, "plans must not be null");
        this.exercises = Objects.requireNonNull(exercises, "exercises must not be null");
    }

    @Override
    public PlanDaySource load(UUID userId, UUID planId, int versionNumber, String trainingDayCode) {
        AuthenticatedUserId user = new AuthenticatedUserId(userId);
        TrainingPlanVersion version;
        try {
            version = plans.getVersion(user, planId, versionNumber);
        } catch (PlanVersionService.PlanNotFoundException | IllegalArgumentException exception) {
            throw new PlanSnapshotNotFoundException();
        }
        PlanDraft.Day day = version.plan().days().stream()
                .filter(candidate -> candidate.code().equals(trainingDayCode))
                .findFirst().orElseThrow(PlanSnapshotNotFoundException::new);
        UUID dayId = stableId("day", version.id(), day.code());
        List<ExerciseSource> sources = java.util.stream.IntStream.range(0, day.exercises().size())
                .mapToObj(index -> source(user, version, day, day.exercises().get(index), index + 1))
                .toList();
        return new PlanDaySource(
                planId, version.id(), version.versionNumber(), dayId, day.code(),
                day.warmup().stream().map(step -> new WarmupStepSource(
                        step.instruction(), Optional.ofNullable(step.prescription()), step.optional())).toList(),
                sources);
    }

    private ExerciseSource source(
            AuthenticatedUserId user,
            TrainingPlanVersion version,
            PlanDraft.Day day,
            PlanDraft.Exercise prescription,
            int order) {
        ExerciseCatalog.Exercise exercise = exercises.get(user, prescription.exerciseCode())
                .orElseThrow(PlanSnapshotNotFoundException::new);
        return new ExerciseSource(
                stableId("exercise", version.id(), day.code() + ":" + exercise.code()), order,
                exercise.code(), exercise.name(), version.ruleReference().contentVersion(), exercise.equipment(),
                prescription.workSets(), prescription.repMin(), prescription.repMax(),
                prescription.restSeconds(), prescription.weightStatus().name(),
                prescription.targetWeightKg(), "KG",
                Optional.ofNullable(prescription.targetRirMin()),
                Optional.ofNullable(prescription.targetRirMax()),
                Optional.ofNullable(prescription.eccentricSeconds()),
                prescription.perSide(), Optional.ofNullable(prescription.executionGroup()),
                prescription.executionOrder() > 0
                        ? Optional.of(prescription.executionOrder()) : Optional.empty(),
                Optional.ofNullable(prescription.optionalSetRule()).map(rule -> new OptionalSetRuleSource(
                        rule.conditionCode(), rule.exclusiveChoiceGroup(), rule.additionalSets(),
                        Optional.empty())));
    }

    private static UUID stableId(String type, UUID versionId, String key) {
        return UUID.nameUUIDFromBytes(
                ("ai-fitness-plan-" + type + ":" + versionId + ":" + key)
                        .getBytes(StandardCharsets.UTF_8));
    }
}
