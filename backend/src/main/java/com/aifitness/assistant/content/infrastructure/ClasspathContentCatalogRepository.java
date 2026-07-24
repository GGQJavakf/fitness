package com.aifitness.assistant.content.infrastructure;

import com.aifitness.assistant.content.application.ContentCatalogRepository;
import com.aifitness.assistant.content.domain.ContentEnvironment;
import com.aifitness.assistant.content.domain.ExerciseCatalog;
import com.aifitness.assistant.content.domain.PlanTemplateCatalog;
import com.aifitness.assistant.content.domain.ReleaseMetadata;
import com.aifitness.assistant.content.domain.ReleaseStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.core.io.ClassPathResource;

public final class ClasspathContentCatalogRepository implements ContentCatalogRepository {

    private static final String PLACEHOLDER = "asset://exercise-placeholder";

    private final ExerciseCatalog exercises;
    private final PlanTemplateCatalog templates;

    public ClasspathContentCatalogRepository(ObjectMapper objectMapper) {
        this.exercises = loadExercises(read(objectMapper, "rule-config/exercises-v1.json"));
        this.templates = loadTemplates(read(objectMapper, "rule-config/plan-templates-v1.json"));
    }

    @Override
    public ExerciseCatalog exercises() {
        return exercises;
    }

    @Override
    public PlanTemplateCatalog templates() {
        return templates;
    }

    private static JsonNode read(ObjectMapper objectMapper, String path) {
        try (InputStream input = new ClassPathResource(path).getInputStream()) {
            return objectMapper.readTree(input);
        } catch (IOException exception) {
            throw new IllegalStateException("validated content catalog cannot be loaded: " + path, exception);
        }
    }

    private static ExerciseCatalog loadExercises(JsonNode document) {
        List<ExerciseCatalog.Exercise> exercises = new ArrayList<>();
        document.path("exercises").forEach(node -> exercises.add(new ExerciseCatalog.Exercise(
                node.path("code").asText(), node.path("name").asText(), node.path("plainLanguage").asText(),
                node.path("movementPattern").asText(), texts(node.path("equipment")),
                texts(node.path("primaryMuscles")), textList(node.path("instructions")),
                textList(node.path("safetyCues")), node.path("rightsStatus").asText(),
                node.path("active").asBoolean(false), image(node), alternatives(node.path("alternatives")))));
        return new ExerciseCatalog(metadata(document.path("metadata")), exercises);
    }

    private static ExerciseCatalog.Image image(JsonNode exercise) {
        if ("PLACEHOLDER_ONLY".equals(exercise.path("assetStatus").asText())) {
            return new ExerciseCatalog.Image(PLACEHOLDER, PLACEHOLDER);
        }
        return new ExerciseCatalog.Image(exercise.path("imageRef").asText(), PLACEHOLDER);
    }

    private static List<ExerciseCatalog.Alternative> alternatives(JsonNode nodes) {
        List<ExerciseCatalog.Alternative> alternatives = new ArrayList<>();
        nodes.forEach(node -> alternatives.add(new ExerciseCatalog.Alternative(
                node.path("exerciseCode").asText(), node.path("rank").asInt(),
                ReleaseStatus.valueOf(node.path("reviewStatus").asText()))));
        return alternatives;
    }

    private static PlanTemplateCatalog loadTemplates(JsonNode document) {
        JsonNode metadataNode = document.path("metadata");
        ReleaseMetadata metadata = new ReleaseMetadata(
                metadataNode.path("version").asText(),
                ReleaseStatus.valueOf(metadataNode.path("status").asText()),
                metadataNode.at("/activation/enabled").asBoolean(false),
                environments(metadataNode.at("/activation/environments")),
                sourceIds(metadataNode.path("sourceReferences")));
        List<PlanTemplateCatalog.Template> templates = new ArrayList<>();
        document.path("templates").forEach(node -> templates.add(new PlanTemplateCatalog.Template(
                node.path("code").asText(), node.path("name").asText(),
                node.path("sessionsPerWeek").asInt(), templateExerciseCodes(node.path("days")),
                templateDays(node.path("days")))));
        return new PlanTemplateCatalog(metadata, metadataNode.path("contentVersion").asText(), templates);
    }

    private static List<PlanTemplateCatalog.Day> templateDays(JsonNode nodes) {
        List<PlanTemplateCatalog.Day> days = new ArrayList<>();
        nodes.forEach(day -> {
            List<PlanTemplateCatalog.ExerciseSlot> exercises = new ArrayList<>();
            day.path("exercises").forEach(slot -> exercises.add(new PlanTemplateCatalog.ExerciseSlot(
                    slot.path("exerciseCode").asText(), slot.path("order").asInt(),
                    slot.path("workSets").asInt(), slot.at("/repRange/min").asInt(),
                    slot.at("/repRange/max").asInt(), slot.path("restSeconds").asInt(),
                    slot.path("initialWeightState").asText())));
            days.add(new PlanTemplateCatalog.Day(
                    day.path("code").asText(), day.path("name").asText(), exercises));
        });
        return List.copyOf(days);
    }

    private static Set<String> templateExerciseCodes(JsonNode days) {
        Set<String> codes = new HashSet<>();
        days.forEach(day -> day.path("exercises").forEach(slot -> codes.add(slot.path("exerciseCode").asText())));
        return codes;
    }

    private static ReleaseMetadata metadata(JsonNode node) {
        return new ReleaseMetadata(
                node.path("version").asText(), ReleaseStatus.valueOf(node.path("status").asText()),
                node.at("/activation/enabled").asBoolean(false),
                environments(node.at("/activation/environments")), sourceIds(node.path("sourceReferences")));
    }

    private static Set<ContentEnvironment> environments(JsonNode nodes) {
        Set<ContentEnvironment> values = new HashSet<>();
        nodes.forEach(node -> values.add(ContentEnvironment.fromExternalName(node.asText())));
        return values;
    }

    private static List<String> sourceIds(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.path("id").asText()));
        return values;
    }

    private static Set<String> texts(JsonNode nodes) {
        return Set.copyOf(textList(nodes));
    }

    private static List<String> textList(JsonNode nodes) {
        List<String> values = new ArrayList<>();
        nodes.forEach(node -> values.add(node.asText()));
        return values;
    }
}
