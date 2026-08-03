package com.aifitness.assistant.rules.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatedConfigValidationTest {

    private static final Path CONFIG_ROOT = Path.of("..", "rule-config");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void validatedCandidatesConformToTheirSchemas() throws IOException {
        assertValid("rule-config.schema.json", "rule-config-v1.json");
        assertValid("plan-template.schema.json", "plan-templates-v1.json");
        assertValid("exercise-content.schema.json", "exercises-v1.json");
    }

    @Test
    void rejectsMissingFieldsIllegalStatusMissingSourcesDraftActivationWrongVersionNonKgAndRanges()
            throws IOException {
        JsonNode valid = readValidated("rule-config-v1.json");

        assertInvalid("rule-config.schema.json", without(valid, "/metadata/version"));
        assertInvalid("rule-config.schema.json", withText(valid, "/metadata/status", "UNKNOWN"));
        assertInvalid("rule-config.schema.json", withArrayCleared(valid, "/metadata/sourceReferences"));
        assertInvalid("rule-config.schema.json", withText(valid, "/metadata/status", "AI_DRAFT"));
        assertInvalid("rule-config.schema.json", withText(valid, "/metadata/schemaVersion", "2.0.0"));
        assertInvalid("rule-config.schema.json", withText(valid, "/scope/unit", "LB"));
        assertInvalid("rule-config.schema.json", withInt(valid, "/parameters/rest/maximumSeconds", 900));
    }

    @Test
    void releasePolicyKeepsUnapprovedContentOutOfPublicAndInactiveStatesOutOfAllEnvironments()
            throws IOException {
        JsonNode valid = readValidated("rule-config-v1.json");

        assertThat(canActivate(valid, "local")).isTrue();
        assertThat(canActivate(valid, "test")).isTrue();
        assertThat(canActivate(valid, "staging-experience")).isTrue();
        assertThat(canActivate(valid, "public")).isFalse();
        assertThat(canActivate(withText(valid, "/metadata/status", "AI_DRAFT"), "local")).isFalse();
        assertThat(canActivate(withText(valid, "/metadata/status", "RETIRED"), "local")).isFalse();
        assertThat(canActivate(withText(valid, "/metadata/status", "PUBLIC_RELEASE_APPROVED"), "public"))
                .isTrue();
    }

    @Test
    void everyConfigKindSupportsTheSameSafeLifecycleStates() throws IOException {
        assertSafeDraft("rule-config.schema.json", "rule-config-v1.json");
        assertSafeDraft("plan-template.schema.json", "plan-templates-v1.json");
        assertSafeDraft("exercise-content.schema.json", "exercises-v1.json");
    }

    @Test
    void publicApprovalRequiresExplicitPublicEnvironmentForEveryConfigKind() throws IOException {
        assertPublicActivationGuard("rule-config.schema.json", "rule-config-v1.json");
        assertPublicActivationGuard("plan-template.schema.json", "plan-templates-v1.json");
        assertPublicActivationGuard("exercise-content.schema.json", "exercises-v1.json");
    }

    @Test
    void templatesResolveCompletelyAgainstTheVersionedExerciseCatalog() throws IOException {
        JsonNode templatesDocument = readValidated("plan-templates-v1.json");
        JsonNode exercisesDocument = readValidated("exercises-v1.json");
        Set<String> exerciseCodes = new HashSet<>();
        exercisesDocument.path("exercises").forEach(exercise -> exerciseCodes.add(exercise.path("code").asText()));

        assertThat(templatesDocument.path("metadata").path("ruleVersion").asText()).isEqualTo("1.1.0");
        assertThat(templatesDocument.path("metadata").path("contentVersion").asText()).isEqualTo("1.1.0");
        templatesDocument.path("templates").forEach(template -> {
            assertThat(template.path("days")).hasSize(template.path("sessionsPerWeek").asInt());
            template.path("days").forEach(day -> day.path("exercises").forEach(slot ->
                    assertThat(exerciseCodes).contains(slot.path("exerciseCode").asText())));
        });
    }

    @Test
    void templateCatalogCoversEveryP0FrequencyFromTwoThroughSixDays() throws IOException {
        Set<Integer> frequencies = new HashSet<>();
        readValidated("plan-templates-v1.json").path("templates")
                .forEach(template -> frequencies.add(template.path("sessionsPerWeek").asInt()));

        assertThat(frequencies).containsExactlyInAnyOrder(2, 3, 4, 5, 6);
    }

    @Test
    void everyP0FrequencyAndEquipmentRangeHasATemplateThatFitsTheMinimumSessionDuration()
            throws IOException {
        JsonNode templates = readValidated("plan-templates-v1.json").path("templates");
        JsonNode rules = readValidated("rule-config-v1.json");
        int secondsPerWorkSet = rules.at("/parameters/duration/secondsPerWorkSet").asInt();
        int secondsPerTransition = rules.at("/parameters/duration/secondsPerExerciseTransition").asInt();
        int minimumSelectableMinutes = 30;

        for (int frequency = 2; frequency <= 6; frequency++) {
            for (boolean bodyweight : new boolean[] {false, true}) {
                int expectedFrequency = frequency;
                boolean expectedBodyweight = bodyweight;
                assertThat(StreamSupport.stream(templates.spliterator(), false)
                                .filter(template -> template.path("sessionsPerWeek").asInt() == expectedFrequency)
                                .filter(template -> template.path("code").asText().contains("BODYWEIGHT")
                                        == expectedBodyweight)
                                .anyMatch(template -> StreamSupport.stream(
                                                template.path("days").spliterator(), false)
                                        .allMatch(day -> estimatedSeconds(
                                                day, secondsPerWorkSet, secondsPerTransition)
                                                <= minimumSelectableMinutes * 60)))
                        .as("frequency=%s, equipment=%s", frequency, bodyweight ? "BODYWEIGHT" : "GYM")
                        .isTrue();
            }
        }
    }

    @Test
    void bodyweightTemplateCatalogCoversEveryP0FrequencyWithoutExternalEquipment() throws IOException {
        JsonNode exercisesDocument = readValidated("exercises-v1.json");
        Set<String> bodyweightExerciseCodes = new HashSet<>();
        exercisesDocument.path("exercises").forEach(exercise -> {
            if (StreamSupport.stream(exercise.path("equipment").spliterator(), false)
                    .map(JsonNode::asText)
                    .anyMatch("BODYWEIGHT"::equals)) {
                bodyweightExerciseCodes.add(exercise.path("code").asText());
            }
        });

        Set<Integer> frequencies = new HashSet<>();
        readValidated("plan-templates-v1.json").path("templates").forEach(template -> {
            if (template.path("code").asText().contains("BODYWEIGHT")) {
                frequencies.add(template.path("sessionsPerWeek").asInt());
                template.path("days").forEach(day -> day.path("exercises").forEach(slot -> {
                    assertThat(bodyweightExerciseCodes).contains(slot.path("exerciseCode").asText());
                    assertThat(slot.path("initialWeightState").asText()).isEqualTo("BODYWEIGHT");
                }));
            }
        });

        assertThat(frequencies).containsExactlyInAnyOrder(2, 3, 4, 5, 6);
    }

    @Test
    void ruleConfigForbidsDemographicWeightGuessingAndKeepsWarmupsOutOfVolume() throws IOException {
        JsonNode rules = readValidated("rule-config-v1.json");

        assertThat(rules.at("/parameters/initialWeight/sourcePriority"))
                .extracting(JsonNode::asText)
                .containsExactly("RECENT_VALID_RECORD", "USER_INPUT", "CALIBRATION", "BODYWEIGHT");
        assertThat(rules.at("/parameters/initialWeight/unknownResult").asText()).isEqualTo("NEEDS_CALIBRATION");
        assertThat(rules.at("/parameters/initialWeight/demographicEstimationAllowed").asBoolean()).isFalse();
        assertThat(rules.at("/parameters/warmup/countsTowardTrainingVolume").asBoolean()).isFalse();
    }

    private static int estimatedSeconds(JsonNode day, int secondsPerWorkSet, int secondsPerTransition) {
        return StreamSupport.stream(day.path("exercises").spliterator(), false)
                .mapToInt(slot -> slot.path("workSets").asInt()
                        * (secondsPerWorkSet + slot.path("restSeconds").asInt())
                        + secondsPerTransition)
                .sum();
    }

    @Test
    void immutableCandidateDigestsMatchCanonicalContent() throws IOException, NoSuchAlgorithmException {
        assertDigest("rule-config-v1.json");
        assertDigest("plan-templates-v1.json");
        assertDigest("exercises-v1.json");
    }

    @Test
    void numericRangesAndOrderingAreInternallyConsistent() throws IOException {
        JsonNode rules = readValidated("rule-config-v1.json");
        assertThat(rules.at("/parameters/planLimits/minimumSessionsPerWeek").asInt())
                .isLessThanOrEqualTo(rules.at("/parameters/planLimits/maximumSessionsPerWeek").asInt());
        assertThat(rules.at("/parameters/prescription/minimumWorkSets").asInt())
                .isLessThanOrEqualTo(rules.at("/parameters/prescription/maximumWorkSets").asInt());
        assertThat(rules.at("/parameters/rest/minimumSeconds").asInt())
                .isLessThanOrEqualTo(rules.at("/parameters/rest/defaultSeconds").asInt())
                .isLessThanOrEqualTo(rules.at("/parameters/rest/maximumSeconds").asInt());

        readValidated("plan-templates-v1.json").path("templates").forEach(template ->
                template.path("days").forEach(day -> day.path("exercises").forEach(slot ->
                        assertThat(slot.at("/repRange/min").asInt())
                                .isLessThanOrEqualTo(slot.at("/repRange/max").asInt()))));
    }

    private static void assertValid(String schemaFile, String documentFile) throws IOException {
        List<com.networknt.schema.Error> errors = validate(schemaFile, readValidated(documentFile));
        assertThat(errors).as(documentFile + " schema errors").isEmpty();
    }

    private static void assertInvalid(String schemaFile, JsonNode document) throws IOException {
        assertThat(validate(schemaFile, document)).isNotEmpty();
    }

    private static void assertSafeDraft(String schemaFile, String documentFile) throws IOException {
        JsonNode draft = withText(readValidated(documentFile), "/metadata/status", "AI_DRAFT");
        draft = withBoolean(draft, "/metadata/activation/enabled", false);
        draft = withArrayCleared(draft, "/metadata/activation/environments");
        assertThat(validate(schemaFile, draft)).as(documentFile + " safe draft errors").isEmpty();
    }

    private static void assertPublicActivationGuard(String schemaFile, String documentFile) throws IOException {
        JsonNode approved = withText(readValidated(documentFile), "/metadata/status", "PUBLIC_RELEASE_APPROVED");
        assertThat(validate(schemaFile, approved)).as(documentFile + " missing public environment").isNotEmpty();
        ((com.fasterxml.jackson.databind.node.ArrayNode) approved.at("/metadata/activation/environments")).add("public");
        assertThat(validate(schemaFile, approved)).as(documentFile + " public approval errors").isEmpty();
    }

    private static List<com.networknt.schema.Error> validate(String schemaFile, JsonNode document) throws IOException {
        String schemaData = Files.readString(CONFIG_ROOT.resolve("schema").resolve(schemaFile));
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema schema = registry.getSchema(schemaData, InputFormat.JSON);
        return schema.validate(document.toString(), InputFormat.JSON, context ->
                context.executionConfig(config -> config.formatAssertionsEnabled(true)));
    }

    private static JsonNode readValidated(String file) throws IOException {
        return JSON.readTree(CONFIG_ROOT.resolve("validated").resolve(file).toFile());
    }

    private static void assertDigest(String file) throws IOException, NoSuchAlgorithmException {
        JsonNode document = readValidated(file);
        String expected = document.at("/metadata/digestSha256").asText();
        ((com.fasterxml.jackson.databind.node.ObjectNode) document.path("metadata")).remove("digestSha256");
        byte[] canonicalBytes = JSON.writeValueAsBytes(canonicalize(document));
        String actual = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonicalBytes));
        assertThat(actual).as(file + " canonical SHA-256").isEqualTo(expected);
    }

    private static JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            com.fasterxml.jackson.databind.node.ObjectNode sorted = JSON.createObjectNode();
            List<String> names = new ArrayList<>();
            node.fieldNames().forEachRemaining(names::add);
            names.stream().sorted(Comparator.naturalOrder()).forEach(name -> sorted.set(name, canonicalize(node.get(name))));
            return sorted;
        }
        if (node.isArray()) {
            com.fasterxml.jackson.databind.node.ArrayNode array = JSON.createArrayNode();
            node.forEach(child -> array.add(canonicalize(child)));
            return array;
        }
        return node.deepCopy();
    }

    private static JsonNode without(JsonNode source, String pointer) {
        JsonNode copy = source.deepCopy();
        String parent = pointer.substring(0, pointer.lastIndexOf('/'));
        String field = pointer.substring(pointer.lastIndexOf('/') + 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.at(parent)).remove(field);
        return copy;
    }

    private static JsonNode withText(JsonNode source, String pointer, String value) {
        JsonNode copy = source.deepCopy();
        String parent = pointer.substring(0, pointer.lastIndexOf('/'));
        String field = pointer.substring(pointer.lastIndexOf('/') + 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.at(parent)).put(field, value);
        return copy;
    }

    private static JsonNode withInt(JsonNode source, String pointer, int value) {
        JsonNode copy = source.deepCopy();
        String parent = pointer.substring(0, pointer.lastIndexOf('/'));
        String field = pointer.substring(pointer.lastIndexOf('/') + 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.at(parent)).put(field, value);
        return copy;
    }

    private static JsonNode withBoolean(JsonNode source, String pointer, boolean value) {
        JsonNode copy = source.deepCopy();
        String parent = pointer.substring(0, pointer.lastIndexOf('/'));
        String field = pointer.substring(pointer.lastIndexOf('/') + 1);
        ((com.fasterxml.jackson.databind.node.ObjectNode) copy.at(parent)).put(field, value);
        return copy;
    }

    private static JsonNode withArrayCleared(JsonNode source, String pointer) {
        JsonNode copy = source.deepCopy();
        ((com.fasterxml.jackson.databind.node.ArrayNode) copy.at(pointer)).removeAll();
        return copy;
    }

    private static boolean canActivate(JsonNode document, String environment) {
        String status = document.path("metadata").path("status").asText();
        if ("AI_DRAFT".equals(status) || "RETIRED".equals(status)) {
            return false;
        }
        if ("public".equals(environment)) {
            return "PUBLIC_RELEASE_APPROVED".equals(status);
        }
        return Set.of("local", "test", "staging-experience").contains(environment)
                && ("AI_VALIDATED".equals(status) || "PUBLIC_RELEASE_APPROVED".equals(status));
    }
}
