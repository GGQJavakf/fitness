package com.aifitness.assistant.plan.infrastructure;

import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ReleaseMetadata;
import com.aifitness.assistant.content.domain.ReleaseStatus;
import com.aifitness.assistant.plan.domain.PlanDraft;
import com.aifitness.assistant.plan.domain.SystemPlanPresetCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

public final class ClasspathSystemPlanPresetCatalogLoader {
    private static final String RESOURCE = "rule-config/plan-presets-v1.json";

    private ClasspathSystemPlanPresetCatalogLoader() {}

    public static SystemPlanPresetCatalog load(
            ObjectMapper objectMapper,
            ContentEnvironment environment) {
        try (InputStream input = new ClassPathResource(RESOURCE).getInputStream()) {
            JsonNode document = objectMapper.readTree(input);
            if (!releaseMetadata(document.path("metadata")).isEligibleFor(environment)) {
                return SystemPlanPresetCatalog.empty();
            }
            List<SystemPlanPresetCatalog.Preset> presets = new ArrayList<>();
            document.path("presets").forEach(node -> presets.add(preset(node)));
            return new SystemPlanPresetCatalog(presets);
        } catch (IOException exception) {
            throw new IllegalStateException("system plan presets cannot be loaded", exception);
        }
    }

    private static ReleaseMetadata releaseMetadata(JsonNode node) {
        Set<ContentEnvironment> environments = new HashSet<>();
        node.at("/activation/environments").forEach(value -> environments.add(
                ContentEnvironment.fromExternalName(value.asText())));
        return new ReleaseMetadata(
                node.path("version").asText(),
                ReleaseStatus.valueOf(node.path("status").asText()),
                node.at("/activation/enabled").asBoolean(false),
                environments,
                texts(node.path("sourceReferences")));
    }

    private static SystemPlanPresetCatalog.Preset preset(JsonNode node) {
        String code = node.path("code").asText();
        String version = node.path("version").asText();
        PlanDraft plan = new PlanDraft(
                node.path("templateCode").asText(),
                PlanDraft.TrainingSplit.valueOf(node.path("trainingSplit").asText()),
                node.path("name").asText(),
                days(node.path("days")),
                java.util.Map.of(),
                code,
                version,
                texts(node.path("executionRules")),
                texts(node.path("progressionRules")));
        return new SystemPlanPresetCatalog.Preset(
                code, version, node.path("name").asText(), node.path("goal").asText(),
                node.path("weeklyFrequency").asInt(), node.path("sessionMinutes").asInt(),
                node.path("location").asText(), plan);
    }

    private static List<PlanDraft.Day> days(JsonNode nodes) {
        List<PlanDraft.Day> values = new ArrayList<>();
        nodes.forEach(node -> values.add(new PlanDraft.Day(
                node.path("code").asText(), node.path("name").asText(), exercises(node.path("exercises")),
                textOrNull(node.path("weekday")), textOrNull(node.path("focus")),
                node.at("/estimatedMinutes/min").asInt(), node.at("/estimatedMinutes/max").asInt(),
                warmup(node.path("warmup")), texts(node.path("notes")))));
        return List.copyOf(values);
    }

    private static List<PlanDraft.WarmupStep> warmup(JsonNode nodes) {
        List<PlanDraft.WarmupStep> values = new ArrayList<>();
        nodes.forEach(node -> values.add(new PlanDraft.WarmupStep(
                node.path("instruction").asText(), textOrNull(node.path("prescription")),
                node.path("optional").asBoolean(false))));
        return List.copyOf(values);
    }

    private static List<PlanDraft.Exercise> exercises(JsonNode nodes) {
        List<PlanDraft.Exercise> values = new ArrayList<>();
        nodes.forEach(node -> values.add(new PlanDraft.Exercise(
                node.path("exerciseCode").asText(), node.path("workSets").asInt(),
                node.at("/repRange/min").asInt(), node.at("/repRange/max").asInt(),
                node.path("restSeconds").asInt(),
                PlanDraft.WeightStatus.valueOf(node.path("weightStatus").asText()),
                decimal(node.path("targetWeightKg")), integerOrNull(node.path("targetRirMin")),
                integerOrNull(node.path("targetRirMax")), integerOrNull(node.path("eccentricSeconds")),
                node.path("perSide").asBoolean(false), textOrNull(node.path("executionGroup")),
                node.path("executionOrder").asInt(0), optionalSetRule(node.path("optionalSetRule")),
                texts(node.path("notes")))));
        return List.copyOf(values);
    }

    private static PlanDraft.OptionalSetRule optionalSetRule(JsonNode node) {
        return node.isObject() ? new PlanDraft.OptionalSetRule(
                node.path("conditionCode").asText(), node.path("exclusiveChoiceGroup").asText(),
                node.path("additionalSets").asInt()) : null;
    }

    private static Optional<BigDecimal> decimal(JsonNode node) {
        return node.isNumber() ? Optional.of(node.decimalValue()) : Optional.empty();
    }

    private static Integer integerOrNull(JsonNode node) {
        return node.isInt() ? node.asInt() : null;
    }

    private static String textOrNull(JsonNode node) {
        return node.isTextual() && !node.asText().isBlank() ? node.asText() : null;
    }

    private static List<String> texts(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.asText()));
        return List.copyOf(values);
    }
}
