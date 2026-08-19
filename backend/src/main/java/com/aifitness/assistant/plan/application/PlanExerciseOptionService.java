package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.content.application.ExerciseQueryService;
import com.aifitness.assistant.content.application.TemplateQueryService;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.TrainingPlan;
import com.aifitness.assistant.profile.application.ProfileService;
import com.aifitness.assistant.rules.domain.PlanGenerationEngine;
import java.math.BigDecimal;
import java.util.Comparator;
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

        PlanTemplateCatalog.Template template = matchingTemplate(user, draft);
        if (template == null) return List.of();

        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        Map<String, ExerciseCatalog.Exercise> eligible = exercises
                .list(user, ExerciseQueryService.Filter.none()).stream()
                .filter(exercise -> !excluded.contains(exercise.stableId()))
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, Function.identity()));
        Map<String, PlanTemplateCatalog.ExerciseSlot> firstPrescription = new LinkedHashMap<>();
        PlanGenerationEngine.SessionFocus targetFocus = sessionFocus(draft, targetDay);
        template.days().stream()
                .filter(day -> PlanGenerationEngine.SessionFocus.infer(day.code(), day.name()) == targetFocus)
                .flatMap(day -> day.exercises().stream())
                .forEach(slot -> firstPrescription.putIfAbsent(slot.exerciseCode(), slot));

        return firstPrescription.values().stream()
                .filter(slot -> !existingCodes.contains(slot.exerciseCode()))
                .filter(slot -> eligible.containsKey(slot.exerciseCode()))
                .map(slot -> toOption(slot, eligible.get(slot.exerciseCode())))
                .sorted(java.util.Comparator.comparing(Option::exerciseCode))
                .toList();
    }

    /**
     * Returns reviewed, source-equivalent replacements for one exercise already present in the active plan.
     * The source plan prescription remains authoritative; this method never substitutes template numbers.
     */
    public List<ReplacementOption> listReplacements(
            AuthenticatedUserId user, UUID planId, String dayCode, String sourceExerciseCode) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        requireStableCode(dayCode, "dayCode");
        requireStableCode(sourceExerciseCode, "sourceExerciseCode");

        TrainingPlan active = plans.getActive(user);
        if (!active.id().equals(planId)) throw new PlanVersionService.PlanNotFoundException();
        PlanDraft.Day targetDay = active.activeVersion().plan().days().stream()
                .filter(day -> day.code().equals(dayCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("dayCode does not exist in the active plan"));
        PlanDraft.Exercise sourcePrescription = targetDay.exercises().stream()
                .filter(exercise -> exercise.exerciseCode().equals(sourceExerciseCode))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "sourceExerciseCode does not exist in the selected plan day"));

        Map<String, ExerciseCatalog.Exercise> catalog = exercises.catalog().stream()
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, Function.identity()));
        ExerciseCatalog.Exercise source = Optional.ofNullable(catalog.get(sourceExerciseCode))
                .orElseThrow(() -> new IllegalArgumentException("source exercise is not in the released catalog"));
        Set<String> existingCodes = targetDay.exercises().stream()
                .map(PlanDraft.Exercise::exerciseCode)
                .collect(Collectors.toUnmodifiableSet());
        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        Map<String, ExerciseCatalog.Exercise> eligible = exercises
                .list(user, ExerciseQueryService.Filter.none()).stream()
                .filter(exercise -> !excluded.contains(exercise.stableId()))
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, Function.identity()));

        return source.alternatives().stream()
                .sorted(Comparator.comparingInt(ExerciseCatalog.Alternative::rank))
                .map(ExerciseCatalog.Alternative::exerciseCode)
                .distinct()
                .filter(code -> !existingCodes.contains(code))
                .map(eligible::get)
                .filter(Objects::nonNull)
                .filter(candidate -> equivalent(source, candidate))
                .limit(4)
                .map(candidate -> toReplacementOption(sourcePrescription, source, candidate))
                .toList();
    }

    private static ReplacementOption toReplacementOption(
            PlanDraft.Exercise sourcePrescription,
            ExerciseCatalog.Exercise source,
            ExerciseCatalog.Exercise candidate) {
        boolean sourceBodyweight = isBodyweight(source.equipment());
        boolean candidateBodyweight = isBodyweight(candidate.equipment());
        PlanDraft.WeightStatus weightStatus;
        Optional<BigDecimal> targetWeightKg;
        if (candidateBodyweight) {
            weightStatus = PlanDraft.WeightStatus.BODYWEIGHT;
            targetWeightKg = Optional.empty();
        } else if (sourceBodyweight || !source.equipment().equals(candidate.equipment())) {
            weightStatus = PlanDraft.WeightStatus.NEEDS_CALIBRATION;
            targetWeightKg = Optional.empty();
        } else {
            weightStatus = sourcePrescription.weightStatus();
            targetWeightKg = sourcePrescription.targetWeightKg();
        }
        return new ReplacementOption(
                candidate.code(), candidate.name(), sourcePrescription.workSets(), sourcePrescription.repMin(),
                sourcePrescription.repMax(), sourcePrescription.restSeconds(), weightStatus, targetWeightKg,
                candidate.movementPattern(), candidate.primaryMuscles().stream().sorted().toList(),
                candidate.equipment().stream().sorted().toList(),
                MatchReason.SAME_PATTERN_MUSCLES_DIFFICULTY);
    }

    private static boolean equivalent(ExerciseCatalog.Exercise source, ExerciseCatalog.Exercise candidate) {
        return candidate.movementPattern().equals(source.movementPattern())
                && candidate.difficulty().equals(source.difficulty())
                && candidate.primaryMuscles().equals(source.primaryMuscles())
                && validLoadMode(source.equipment())
                && validLoadMode(candidate.equipment());
    }

    private static boolean validLoadMode(Set<String> equipment) {
        return !equipment.isEmpty() && (isBodyweight(equipment) || !equipment.contains("BODYWEIGHT"));
    }

    private static boolean isBodyweight(Set<String> equipment) {
        return equipment.size() == 1 && equipment.contains("BODYWEIGHT");
    }

    private static void requireStableCode(String value, String field) {
        if (value == null || !value.matches("[A-Z0-9_]{1,64}")) {
            throw new IllegalArgumentException(field + " must be a stable code");
        }
    }

    private static PlanGenerationEngine.SessionFocus sessionFocus(PlanDraft draft, PlanDraft.Day targetDay) {
        int dayIndex = draft.days().indexOf(targetDay);
        if ("AI_PERSONALIZED".equals(draft.templateCode()) && draft.trainingSplit() != null) {
            return PlanGenerationEngine.SessionFocus.forSplitIndex(
                    PlanGenerationEngine.TrainingSplit.valueOf(draft.trainingSplit().name()),
                    draft.days().size(), dayIndex);
        }
        PlanGenerationEngine.SessionFocus namedFocus =
                PlanGenerationEngine.SessionFocus.infer(targetDay.code(), targetDay.name());
        if (namedFocus != PlanGenerationEngine.SessionFocus.FULL_BODY || draft.trainingSplit() == null) {
            return namedFocus;
        }
        return PlanGenerationEngine.SessionFocus.forSplitIndex(
                PlanGenerationEngine.TrainingSplit.valueOf(draft.trainingSplit().name()),
                draft.days().size(), dayIndex);
    }

    public List<DayOption> listDays(AuthenticatedUserId user, UUID planId) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(planId, "planId must not be null");
        TrainingPlan active = plans.getActive(user);
        if (!active.id().equals(planId)) throw new PlanVersionService.PlanNotFoundException();
        PlanDraft draft = active.activeVersion().plan();
        PlanTemplateCatalog.Template template = matchingTemplate(user, draft);
        if (template == null) return List.of();

        Set<UUID> excluded = profiles.excludedExerciseIds(user);
        Map<String, ExerciseCatalog.Exercise> eligible = exercises
                .list(user, ExerciseQueryService.Filter.none()).stream()
                .filter(exercise -> !excluded.contains(exercise.stableId()))
                .collect(Collectors.toUnmodifiableMap(ExerciseCatalog.Exercise::code, Function.identity()));
        return template.days().stream()
                .filter(day -> day.exercises().stream().allMatch(slot -> eligible.containsKey(slot.exerciseCode())))
                .map(day -> new DayOption(
                        day.code(), day.name(), day.exercises().stream()
                                .sorted(java.util.Comparator.comparingInt(PlanTemplateCatalog.ExerciseSlot::order))
                                .map(slot -> toOption(slot, eligible.get(slot.exerciseCode())))
                                .toList()))
                .toList();
    }

    private PlanTemplateCatalog.Template matchingTemplate(AuthenticatedUserId user, PlanDraft draft) {
        List<PlanTemplateCatalog.Template> available = templates.list(user, Optional.empty());
        Optional<PlanTemplateCatalog.Template> exact = available.stream()
                .filter(value -> value.code().equals(draft.templateCode()))
                .findFirst();
        if (exact.isPresent() || draft.trainingSplit() == null) return exact.orElse(null);
        String prefix = switch (draft.trainingSplit()) {
            case UPPER_LOWER -> "UPPER_LOWER_";
            case PUSH_PULL_LEGS -> "PUSH_PULL_LEGS_";
            case BODY_PART_FIVE_DAY -> "BODY_PART_";
        };
        return available.stream()
                .filter(value -> value.sessionsPerWeek() == draft.days().size())
                .filter(value -> value.code().startsWith(prefix))
                .findFirst()
                .orElse(null);
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

    public record ReplacementOption(
            String exerciseCode,
            String name,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            PlanDraft.WeightStatus weightStatus,
            Optional<BigDecimal> targetWeightKg,
            String movementPattern,
            List<String> primaryMuscles,
            List<String> equipment,
            MatchReason matchReason) {
        public ReplacementOption {
            targetWeightKg = Objects.requireNonNull(targetWeightKg, "targetWeightKg must not be null");
            primaryMuscles = List.copyOf(primaryMuscles);
            equipment = List.copyOf(equipment);
        }
    }

    public enum MatchReason {
        SAME_PATTERN_MUSCLES_DIFFICULTY
    }

    public record DayOption(String code, String name, List<Option> exercises) {
        public DayOption {
            exercises = List.copyOf(exercises);
        }
    }
}
