package com.aifitness.assistant.rules.infrastructure;

import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
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
            JsonNode duration = parameters.path("duration");
            JsonNode balance = parameters.path("balance");
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
                            requiredInt(duration, "secondsPerExerciseTransition")),
                    new PlanRulePolicy.Balance(
                            requiredInt(balance, "maximumMovementPatternOccurrencesPerSession"),
                            requiredInt(balance, "maximumWorkSetsPerPrimaryMusclePerSession"),
                            requiredInt(balance, "minimumRecoveryHoursBetweenPrimaryMuscleSessions")));
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

    private static String requiredText(JsonNode value, String field) {
        if (!value.isTextual() || value.asText().isBlank()) {
            throw new IllegalStateException("validated rule field is missing: " + field);
        }
        return value.asText();
    }
}
