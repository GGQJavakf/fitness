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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
            Map<String, SystemPlanPresetCatalog.Source> sources =
                    sourceRegistry(document.at("/metadata/sources"));
            List<SystemPlanPresetCatalog.Preset> presets = new ArrayList<>();
            document.path("presets").forEach(node -> presets.add(preset(node, sources)));
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

    private static SystemPlanPresetCatalog.Preset preset(
            JsonNode node,
            Map<String, SystemPlanPresetCatalog.Source> sources) {
        String code = node.path("code").asText();
        String version = node.path("version").asText();
        PlanDraft plan = new PlanDraft(
                node.path("templateCode").asText(),
                trainingSplit(node.path("trainingSplit")),
                node.path("name").asText(),
                days(node.path("days")),
                java.util.Map.of(),
                code,
                version,
                texts(node.path("executionRules")),
                texts(node.path("progressionRules")),
                movementImpactConstraint(node.path("movementImpactConstraint")));
        return new SystemPlanPresetCatalog.Preset(
                code, version, node.path("name").asText(), node.path("experience").asText(),
                node.path("goal").asText(),
                node.path("weeklyFrequency").asInt(), node.path("sessionMinutes").asInt(),
                node.path("location").asText(), plan,
                SystemPlanPresetCatalog.ContentStatus.valueOf(node.path("contentStatus").asText()),
                SystemPlanPresetCatalog.ProfessionalReviewStatus.valueOf(
                        node.path("professionalReviewStatus").asText()),
                textOrNull(node.path("reviewRecordId")), textOrNull(node.path("reviewedAt")),
                availabilityStatus(node.path("availabilityStatus")),
                textOrNull(node.path("unavailableReason")),
                introductoryPhase(node.path("introductoryPhase")),
                resolveSources(node.path("sourceIds"), sources, "sourceIds"),
                resolveSources(node.path("explanationSourceIds"), sources, "explanationSourceIds"));
    }

    static SystemPlanPresetCatalog.AvailabilityStatus availabilityStatus(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(
                    "system plan preset availabilityStatus must be a non-blank string");
        }
        try {
            return SystemPlanPresetCatalog.AvailabilityStatus.valueOf(node.asText());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(
                    "system plan preset availabilityStatus is unsupported: " + node.asText(),
                    exception);
        }
    }

    private static SystemPlanPresetCatalog.IntroductoryPhase introductoryPhase(JsonNode node) {
        return node.isObject()
                ? new SystemPlanPresetCatalog.IntroductoryPhase(
                        node.path("weeks").asInt(), node.path("workSets").asInt(),
                        node.path("targetRirMin").asInt(), node.path("targetRirMax").asInt(),
                        node.path("transitionCondition").asText())
                : null;
    }

    private static PlanDraft.MovementImpactConstraint movementImpactConstraint(JsonNode node) {
        return node.isTextual() ? PlanDraft.MovementImpactConstraint.valueOf(node.asText()) : null;
    }

    static Map<String, SystemPlanPresetCatalog.Source> sourceRegistry(JsonNode nodes) {
        Map<String, SystemPlanPresetCatalog.Source> sources = new LinkedHashMap<>();
        nodes.forEach(node -> {
            SystemPlanPresetCatalog.Source source = new SystemPlanPresetCatalog.Source(
                    node.path("id").asText(),
                    node.path("title").asText(),
                    textOrNull(node.path("url")),
                    textOrNull(node.path("internalSource")),
                    node.path("usageBoundary").asText(),
                    SystemPlanPresetCatalog.SourceKind.valueOf(node.path("sourceKind").asText()));
            if (sources.putIfAbsent(source.id(), source) != null) {
                throw new IllegalArgumentException(
                        "system plan preset source id is duplicate: " + source.id());
            }
        });
        return Map.copyOf(sources);
    }

    static List<SystemPlanPresetCatalog.Source> resolveSources(
            JsonNode nodes,
            Map<String, SystemPlanPresetCatalog.Source> sources,
            String field) {
        List<SystemPlanPresetCatalog.Source> resolved = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        nodes.forEach(node -> {
            String id = node.asText();
            if (!seen.add(id)) {
                throw new IllegalArgumentException(field + " contains duplicate source id: " + id);
            }
            SystemPlanPresetCatalog.Source source = sources.get(id);
            if (source == null) {
                throw new IllegalArgumentException(field + " references unknown source id: " + id);
            }
            resolved.add(source);
        });
        return List.copyOf(resolved);
    }

    static PlanDraft.TrainingSplit trainingSplit(JsonNode node) {
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException("system plan preset trainingSplit must be a non-blank string");
        }
        return PlanDraft.TrainingSplit.valueOf(node.asText());
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
