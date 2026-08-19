package com.aifitness.assistant.rules.infrastructure;

import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

public final class ClasspathPlanRulePolicyLoader {

    private static final String PATH = "rule-config/rule-config-v1.json";

    private ClasspathPlanRulePolicyLoader() {}

    public static PlanRulePolicy load(ObjectMapper objectMapper) {
        Objects.requireNonNull(objectMapper, "objectMapper must not be null");
        try (InputStream input = new ClassPathResource(PATH).getInputStream()) {
            JsonNode root = objectMapper.readTree(input);
            JsonNode parameters = root.path("parameters");
            JsonNode plan = parameters.path("planLimits");
            JsonNode prescription = parameters.path("prescription");
            JsonNode rest = parameters.path("rest");
            JsonNode warmup = parameters.path("warmup");
            JsonNode duration = parameters.path("duration");
            JsonNode balance = parameters.path("balance");
            JsonNode sessionComposition = parameters.path("sessionComposition");
            JsonNode goalPrescriptionsNode = parameters.path("goalPrescriptions");
            List<PlanRulePolicy.ExerciseCountTarget> exerciseCountTargets = new ArrayList<>();
            sessionComposition.path("targetExercisesByMinutes").forEach(target ->
                    exerciseCountTargets.add(new PlanRulePolicy.ExerciseCountTarget(
                            requiredInt(target, "sessionMinutes"),
                            requiredInt(target, "minimumExercises"),
                            requiredInt(target, "maximumExercises"))));
            Map<com.aifitness.assistant.rules.domain.PlanGenerationEngine.FitnessGoal,
                    PlanRulePolicy.GoalPrescription> goalPrescriptions = new LinkedHashMap<>();
            goalPrescriptionsNode.forEach(goalPrescription -> {
                com.aifitness.assistant.rules.domain.PlanGenerationEngine.FitnessGoal goal =
                        com.aifitness.assistant.rules.domain.PlanGenerationEngine.FitnessGoal.valueOf(
                                requiredText(goalPrescription.path("goal"), "goalPrescriptions.goal"));
                goalPrescriptions.put(
                        goal,
                        new PlanRulePolicy.GoalPrescription(
                                requiredInt(goalPrescription, "workSets"),
                                requiredInt(goalPrescription, "repMin"),
                                requiredInt(goalPrescription, "repMax"),
                                requiredInt(goalPrescription, "restSeconds")));
            });
            int rampWarmupSetsPerSession = requiredInt(duration, "rampWarmupSetsPerSession");
            int maximumRampSets = requiredInt(warmup, "maximumRampSets");
            validateRampWarmupSets(rampWarmupSetsPerSession, maximumRampSets);
            List<BigDecimal> knownWorkWeightRatios = new ArrayList<>();
            warmup.path("knownWorkWeightRatios").forEach(ratio -> {
                if (!ratio.isNumber()) {
                    throw new IllegalStateException("validated rule field is missing: knownWorkWeightRatios");
                }
                knownWorkWeightRatios.add(ratio.decimalValue());
            });
            List<Integer> rampSetReps = new ArrayList<>();
            warmup.path("rampSetReps").forEach(reps -> {
                if (!reps.isIntegralNumber()) {
                    throw new IllegalStateException("validated rule field is missing: rampSetReps");
                }
                rampSetReps.add(reps.asInt());
            });
            Set<String> eligibleLoadedCompoundMovementPatterns = new LinkedHashSet<>();
            warmup.path("eligibleLoadedCompoundMovementPatterns").forEach(pattern ->
                    eligibleLoadedCompoundMovementPatterns.add(requiredText(
                            pattern, "warmup.eligibleLoadedCompoundMovementPatterns")));
            List<PlanRulePolicy.WeeklyMovementPatternTargetSet> weeklyMovementPatternTargets = new ArrayList<>();
            balance.path("weeklyMovementPatternTargets").forEach(targetSet -> {
                List<PlanRulePolicy.MovementPatternSessionTarget> targets = new ArrayList<>();
                targetSet.path("targets").forEach(target -> targets.add(
                        new PlanRulePolicy.MovementPatternSessionTarget(
                                requiredText(target.path("movementPattern"),
                                        "balance.weeklyMovementPatternTargets.movementPattern"),
                                requiredInt(target, "minimumSessions"),
                                requiredInt(target, "maximumSessions"))));
                weeklyMovementPatternTargets.add(new PlanRulePolicy.WeeklyMovementPatternTargetSet(
                        requiredInt(targetSet, "sessionsPerWeek"), targets));
            });
            return new PlanRulePolicy(
                    requiredText(root.at("/metadata/version"), "metadata.version"),
                    new PlanRulePolicy.PlanLimits(
                            requiredInt(plan, "minimumSessionsPerWeek"),
                            requiredInt(plan, "maximumSessionsPerWeek"),
                            requiredInt(plan, "maximumExercisesPerSession"),
                            requiredInt(plan, "maximumEstimatedMinutes")),
                    new PlanRulePolicy.Prescription(
                            requiredInt(prescription, "minimumWorkSets"),
                            requiredInt(prescription, "maximumWorkSets"),
                            requiredInt(prescription, "minimumReps"),
                            requiredInt(prescription, "maximumReps")),
                    new PlanRulePolicy.Rest(
                            requiredInt(rest, "minimumSeconds"), requiredInt(rest, "maximumSeconds")),
                    new PlanRulePolicy.Duration(
                            requiredInt(duration, "secondsPerWorkSet"),
                            requiredInt(duration, "secondsPerWarmupSet"),
                            requiredInt(duration, "secondsPerExerciseTransition"),
                            requiredInt(duration, "generalWarmupSeconds"),
                            rampWarmupSetsPerSession),
                    new PlanRulePolicy.Balance(
                            requiredInt(balance, "maximumMovementPatternOccurrencesPerSession"),
                            requiredInt(balance, "maximumWorkSetsPerPrimaryMusclePerSession"),
                            requiredInt(balance, "minimumRecoveryHoursBetweenPrimaryMuscleSessions"),
                            weeklyMovementPatternTargets),
                    new PlanRulePolicy.SessionComposition(
                            requiredInt(sessionComposition, "accessoryWorkSets"),
                            requiredInt(sessionComposition, "accessoryRepMin"),
                            requiredInt(sessionComposition, "accessoryRepMax"),
                            requiredInt(sessionComposition, "accessoryRestSeconds"),
                            exerciseCountTargets),
                    goalPrescriptions,
                    new PlanRulePolicy.Warmup(
                            maximumRampSets,
                            knownWorkWeightRatios,
                            rampSetReps,
                            eligibleLoadedCompoundMovementPatterns,
                            requiredText(warmup.path("unknownWeightResult"), "warmup.unknownWeightResult"),
                            requiredBoolean(warmup, "countsTowardTrainingVolume")));
        } catch (IOException exception) {
            throw new IllegalStateException("validated plan rule policy cannot be loaded", exception);
        }
    }

    private static int requiredInt(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isIntegralNumber()) {
            throw new IllegalStateException("validated rule field is missing: " + field);
        }
        return value.asInt();
    }

    private static boolean requiredBoolean(JsonNode parent, String field) {
        JsonNode value = parent.path(field);
        if (!value.isBoolean()) {
            throw new IllegalStateException("validated rule field is missing: " + field);
        }
        return value.asBoolean();
    }

    static void validateRampWarmupSets(int rampWarmupSetsPerSession, int maximumRampSets) {
        if (rampWarmupSetsPerSession > maximumRampSets) {
            throw new IllegalStateException(
                    "duration.rampWarmupSetsPerSession must not exceed warmup.maximumRampSets");
        }
    }

    private static String requiredText(JsonNode value, String field) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("validated rule field is missing: " + field);
        }
        return value.asText();
    }
}
