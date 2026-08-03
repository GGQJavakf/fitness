package com.aifitness.assistant.ai.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.plan.application.PlanCandidateService;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.progression.application.RecommendationService;
import com.aifitness.assistant.progression.domain.ProgressionRecommendation;
import com.aifitness.assistant.workout.application.WorkoutHistoryQueryService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public final class AiContentService {
    private final PlanCandidateService candidates;
    private final WorkoutHistoryQueryService workouts;
    private final RecommendationService recommendations;
    private final AiOrchestrator orchestrator;
    private final AiOutputValidator validator;
    private final Map<String, String> templates;

    public AiContentService(
            PlanCandidateService candidates,
            WorkoutHistoryQueryService workouts,
            RecommendationService recommendations,
            AiOrchestrator orchestrator,
            AiOutputValidator validator,
            Map<String, String> templates) {
        this.candidates = Objects.requireNonNull(candidates);
        this.workouts = Objects.requireNonNull(workouts);
        this.recommendations = Objects.requireNonNull(recommendations);
        this.orchestrator = Objects.requireNonNull(orchestrator);
        this.validator = Objects.requireNonNull(validator);
        this.templates = Map.copyOf(Objects.requireNonNull(templates));
    }

    public GeneratedContent explainPlan(AuthenticatedUserId user, String candidateId) {
        PlanCandidateService.CandidateEnvelope candidate = candidates.candidate(user, candidateId);
        Set<BigDecimal> numbers = planNumbers(candidate.plan());
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("candidateId", candidate.candidateId());
        input.put("candidateSummaries", planSummary(candidate.plan()));
        String template = template("PLAN_EXPLANATION_DEFAULT")
                .replace("{ruleVersion}", candidate.ruleReference().ruleVersion());
        return validated(AiProvider.Purpose.PLAN_EXPLANATION, input, template, numbers, Set.of());
    }

    public GeneratedContent summarizeWorkout(AuthenticatedUserId user, UUID sessionId) {
        WorkoutHistoryQueryService.Summary workout = workouts.summary(user, sessionId);
        List<ProgressionRecommendation> decisions = recommendations.list(user, Optional.empty()).stream()
                .filter(item -> item.sourceSessionId().equals(sessionId)).toList();
        Set<BigDecimal> numbers = new LinkedHashSet<>();
        numbers.add(BigDecimal.valueOf(workout.completedWorkSets()));
        numbers.add(workout.completedVolumeKg());
        numbers.add(BigDecimal.valueOf(workout.completedReps()));
        decisions.forEach(item -> {
            numbers.add(item.currentPrescription().weightKg());
            numbers.add(item.recommendedPrescription().weightKg());
            numbers.add(BigDecimal.valueOf(item.currentPrescription().repMin()));
            numbers.add(BigDecimal.valueOf(item.currentPrescription().repMax()));
        });
        Set<String> decisionNames = decisions.stream()
                .map(item -> item.decision().name()).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Map<String, Object> facts = new LinkedHashMap<>();
        facts.put("workoutSessionId", sessionId.toString());
        facts.put("status", workout.status().name());
        facts.put("completedWorkSets", workout.completedWorkSets());
        facts.put("completedVolumeKg", workout.completedVolumeKg());
        facts.put("completedReps", workout.completedReps());
        facts.put("usesExternalLoad", workout.usesExternalLoad());
        facts.put("reasonCodes", decisions.stream().map(ProgressionRecommendation::reasonCode).distinct().toList());
        facts.put("decision", decisionNames.stream().sorted().toList());
        String template = template(workout.usesExternalLoad()
                ? "WORKOUT_SUMMARY_DEFAULT" : "WORKOUT_SUMMARY_BODYWEIGHT_DEFAULT")
                .replace("{completedWorkSets}", Integer.toString(workout.completedWorkSets()))
                .replace("{completedVolumeKg}", workout.completedVolumeKg().toPlainString())
                .replace("{completedReps}", Integer.toString(workout.completedReps()));
        return validated(
                AiProvider.Purpose.WORKOUT_SUMMARY,
                Map.of("workoutFacts", facts),
                template,
                numbers,
                decisionNames);
    }

    private GeneratedContent validated(
            AiProvider.Purpose purpose,
            Map<String, ?> input,
            String template,
            Set<BigDecimal> numbers,
            Set<String> decisions) {
        AiOrchestrator.Result generated = orchestrator.generate(purpose, input, template);
        if (generated.status() == AiOrchestrator.Status.DEGRADED) {
            return GeneratedContent.degraded(template, generated.validationStatus());
        }
        AiOutputValidator.ValidationResult validation = validator.validate(
                generated.content(), new AiOutputValidator.AuthoritativeFacts(numbers, decisions));
        if (validation.status() != AiOutputValidator.ValidationStatus.VALID) {
            return GeneratedContent.degraded(template, validation.status().name());
        }
        AiOutputValidator.AiSummary summary = validation.summary().orElseThrow();
        return new GeneratedContent(Status.READY, summary.explanation(), validation.status().name(), Optional.of(summary));
    }

    private String template(String key) {
        String value = templates.get(key);
        if (value == null || value.isBlank()) throw new IllegalStateException("AI template is missing: " + key);
        return value;
    }

    private static Set<BigDecimal> planNumbers(PlanDraft plan) {
        Set<BigDecimal> values = new LinkedHashSet<>();
        plan.days().forEach(day -> day.exercises().forEach(exercise -> {
            values.add(BigDecimal.valueOf(exercise.workSets()));
            values.add(BigDecimal.valueOf(exercise.repMin()));
            values.add(BigDecimal.valueOf(exercise.repMax()));
            values.add(BigDecimal.valueOf(exercise.restSeconds()));
            exercise.targetWeightKg().ifPresent(values::add);
        }));
        return Set.copyOf(values);
    }

    private static List<Map<String, Object>> planSummary(PlanDraft plan) {
        List<Map<String, Object>> days = new ArrayList<>();
        plan.days().forEach(day -> {
            Map<String, Object> dayData = new LinkedHashMap<>();
            dayData.put("dayCode", day.code());
            dayData.put("exercises", day.exercises().stream().map(exercise -> {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("exerciseCode", exercise.exerciseCode());
                item.put("workSets", exercise.workSets());
                item.put("repMin", exercise.repMin());
                item.put("repMax", exercise.repMax());
                item.put("restSeconds", exercise.restSeconds());
                item.put("weightStatus", exercise.weightStatus().name());
                exercise.targetWeightKg().ifPresent(value -> item.put("targetWeightKg", value));
                return Map.copyOf(item);
            }).toList());
            days.add(Map.copyOf(dayData));
        });
        return List.copyOf(days);
    }

    public enum Status { READY, DEGRADED }

    public record GeneratedContent(
            Status status,
            String content,
            String validationStatus,
            Optional<AiOutputValidator.AiSummary> structured) {
        static GeneratedContent degraded(String template, String validationStatus) {
            return new GeneratedContent(Status.DEGRADED, template, validationStatus, Optional.empty());
        }
    }
}
