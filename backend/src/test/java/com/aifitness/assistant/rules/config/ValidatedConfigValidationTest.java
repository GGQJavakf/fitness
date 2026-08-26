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
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatedConfigValidationTest {

    private static final Path CONFIG_ROOT = Path.of("..", "rule-config");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> REVIEW_REQUIRED_REPLACEMENT_SEMANTICS = Set.of(
            "LAT_PULLDOWN", "CABLE_STRAIGHT_ARM_PULLDOWN", "NEUTRAL_GRIP_PULLDOWN",
            "PRONE_W_RAISE", "PRONE_Y_RAISE", "GLUTE_BRIDGE_EXERCISE",
            "FLOOR_PRONE_COBRA", "CONTRALATERAL_LIMB_RAISE", "STANDING_WALL_CALF_RAISE",
            "SMITH_FLAT_BENCH_PRESS", "INCLINE_DUMBBELL_BENCH_PRESS_30",
            "SEATED_MACHINE_SHOULDER_PRESS", "LEANING_PEC_DECK_FLY", "MACHINE_SEATED_ROW",
            "REVERSE_PEC_DECK_FLY", "SMITH_SQUAT", "SEATED_LEG_PRESS",
            "DUMBBELL_REVERSE_LUNGE", "SEATED_LEG_EXTENSION", "MACHINE_CRUNCH",
            "INCLINE_DUMBBELL_FLY", "MACHINE_HIP_THRUST", "MACHINE_LEG_CURL",
            "MACHINE_HIP_ABDUCTION", "STANDING_CALF_RAISE");
    private static final Map<String, Set<String>> REVIEW_REQUIRED_ENVIRONMENT_GAPS = Map.of(
            "HOME", Set.of(
                    "BODYWEIGHT_SQUAT", "BODYWEIGHT_HIP_HINGE", "PRISONER_SQUAT",
                    "PRONE_W_RAISE", "PRONE_Y_RAISE", "GLUTE_BRIDGE_EXERCISE",
                    "FLOOR_PRONE_COBRA", "CONTRALATERAL_LIMB_RAISE", "STANDING_WALL_CALF_RAISE"),
            "GYM", Set.of(
                    "LAT_PULLDOWN", "CABLE_STRAIGHT_ARM_PULLDOWN", "NEUTRAL_GRIP_PULLDOWN",
                    "PRONE_W_RAISE", "PRONE_Y_RAISE", "GLUTE_BRIDGE_EXERCISE",
                    "FLOOR_PRONE_COBRA", "CONTRALATERAL_LIMB_RAISE", "STANDING_WALL_CALF_RAISE",
                    "SMITH_FLAT_BENCH_PRESS", "INCLINE_DUMBBELL_BENCH_PRESS_30",
                    "SEATED_MACHINE_SHOULDER_PRESS", "LEANING_PEC_DECK_FLY", "MACHINE_SEATED_ROW",
                    "REVERSE_PEC_DECK_FLY", "SMITH_SQUAT", "SEATED_LEG_PRESS",
                    "DUMBBELL_REVERSE_LUNGE", "SEATED_LEG_EXTENSION", "MACHINE_CRUNCH",
                    "INCLINE_DUMBBELL_FLY", "MACHINE_HIP_THRUST", "MACHINE_LEG_CURL",
                    "MACHINE_HIP_ABDUCTION", "STANDING_CALF_RAISE"));

    @Test
    void validatedCandidatesConformToTheirSchemas() throws IOException {
        assertValid("rule-config.schema.json", "rule-config-v1.json");
        assertValid("plan-template.schema.json", "plan-templates-v1.json");
        assertValid("plan-preset.schema.json", "plan-presets-v1.json");
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
        assertInvalid("rule-config.schema.json", without(valid, "/parameters/progression"));
        assertInvalid("rule-config.schema.json", withInt(valid, "/parameters/progression/longTrainingGapDays", 6));
        assertInvalid("rule-config.schema.json", withInt(valid, "/parameters/progression/multipleFailedSetsThreshold", 1));
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
        assertSafeDraft("plan-preset.schema.json", "plan-presets-v1.json");
        assertSafeDraft("exercise-content.schema.json", "exercises-v1.json");
    }

    @Test
    void publicApprovalRequiresExplicitPublicEnvironmentForEveryConfigKind() throws IOException {
        assertPublicActivationGuard("rule-config.schema.json", "rule-config-v1.json");
        assertPublicActivationGuard("plan-template.schema.json", "plan-templates-v1.json");
        assertPublicActivationGuard("plan-preset.schema.json", "plan-presets-v1.json");
        assertPublicActivationGuard("exercise-content.schema.json", "exercises-v1.json");
    }

    @Test
    void personalFiveDayPresetKeepsWarmupTargetAndIsolationFailureBoundary() throws IOException {
        JsonNode preset = readValidated("plan-presets-v1.json").path("presets").get(0);
        List<List<String>> notesByDay = StreamSupport.stream(preset.path("days").spliterator(), false)
                .map(day -> StreamSupport.stream(day.path("notes").spliterator(), false)
                        .map(JsonNode::asText)
                        .toList())
                .toList();
        List<String> executionRules = StreamSupport.stream(
                        preset.path("executionRules").spliterator(), false)
                .map(JsonNode::asText)
                .toList();

        assertThat(preset.path("code").asText()).isEqualTo("PERSONAL_5_DAY_HYPERTROPHY_V1");
        assertThat(notesByDay).hasSize(5)
                .allSatisfy(notes -> assertThat(notes).contains("热身目标 4～6 分钟"));
        assertThat(executionRules)
                .contains("复合动作保留约 2 次余力；孤立动作前面正式组保留约 2 次余力，仅最后一组可保留 1 次余力并接近力竭");
        assertThat(String.join("，", executionRules))
                .doesNotContain("孤立动作保留 1～2 次余力");
    }

    @Test
    void templatesResolveCompletelyAgainstTheVersionedExerciseCatalog() throws IOException {
        JsonNode templatesDocument = readValidated("plan-templates-v1.json");
        JsonNode exercisesDocument = readValidated("exercises-v1.json");
        Set<String> exerciseCodes = new HashSet<>();
        exercisesDocument.path("exercises").forEach(exercise -> exerciseCodes.add(exercise.path("code").asText()));

        assertThat(templatesDocument.path("metadata").path("ruleVersion").asText()).isEqualTo("1.6.0");
        assertThat(templatesDocument.path("metadata").path("contentVersion").asText()).isEqualTo("1.8.0");
        templatesDocument.path("templates").forEach(template -> {
            assertThat(template.path("days")).hasSize(template.path("sessionsPerWeek").asInt());
            template.path("days").forEach(day -> day.path("exercises").forEach(slot ->
                    assertThat(exerciseCodes).contains(slot.path("exerciseCode").asText())));
        });
    }

    @Test
    void professionalGymSplitsUseDistinctPatternsAndDirectArmWork() throws IOException {
        JsonNode exerciseNodes = readValidated("exercises-v1.json").path("exercises");
        Map<String, String> patterns = new java.util.LinkedHashMap<>();
        exerciseNodes.forEach(exercise -> patterns.put(
                exercise.path("code").asText(), exercise.path("movementPattern").asText()));

        assertThat(patterns).hasSize(63).containsKeys(
                "DUMBBELL_BICEPS_CURL", "DUMBBELL_HAMMER_CURL", "CABLE_BICEPS_CURL",
                "CABLE_TRICEPS_PUSHDOWN", "DUMBBELL_OVERHEAD_TRICEPS_EXTENSION",
                "DUMBBELL_LYING_TRICEPS_EXTENSION", "DUMBBELL_LATERAL_RAISE",
                "CABLE_FACE_PULL", "ONE_ARM_DUMBBELL_ROW", "DUMBBELL_SHRUG");

        readValidated("plan-templates-v1.json").path("templates").forEach(template -> {
            if (!Set.of("UPPER_LOWER_4_DAY_V1", "HYBRID_5_DAY_V1", "PUSH_PULL_LEGS_6_DAY_V1")
                    .contains(template.path("code").asText())) {
                return;
            }
            template.path("days").forEach(day -> {
                List<String> dayPatterns = StreamSupport.stream(day.path("exercises").spliterator(), false)
                        .map(slot -> patterns.get(slot.path("exerciseCode").asText()))
                        .toList();
                assertThat(dayPatterns).as(day.path("code").asText()).doesNotHaveDuplicates();
                assertThat(day.path("exercises").size()).isBetween(4, 5);
                String dayCode = day.path("code").asText();
                if (dayCode.startsWith("PUSH")) {
                    assertThat(dayPatterns).contains(
                            "HORIZONTAL_PUSH", "VERTICAL_PUSH", "ELBOW_EXTENSION")
                            .doesNotContain("SQUAT", "HINGE", "ELBOW_FLEXION");
                } else if (dayCode.startsWith("PULL")) {
                    assertThat(dayPatterns).contains(
                            "HORIZONTAL_PULL", "VERTICAL_PULL", "ELBOW_FLEXION")
                            .doesNotContain("SQUAT", "HINGE", "ELBOW_EXTENSION");
                } else if (dayCode.startsWith("UPPER")) {
                    assertThat(dayPatterns).contains(
                            "HORIZONTAL_PUSH", "HORIZONTAL_PULL", "ELBOW_FLEXION", "ELBOW_EXTENSION")
                            .doesNotContain("SQUAT", "HINGE");
                }
            });
        });
    }

    @Test
    void fullBodyGymTemplatesCoverDirectBicepsAndTricepsAcrossTheWeek() throws IOException {
        JsonNode exerciseNodes = readValidated("exercises-v1.json").path("exercises");
        Map<String, String> patterns = new java.util.LinkedHashMap<>();
        exerciseNodes.forEach(exercise -> patterns.put(
                exercise.path("code").asText(), exercise.path("movementPattern").asText()));

        readValidated("plan-templates-v1.json").path("templates").forEach(template -> {
            if (!Set.of("FULL_BODY_2_DAY_V1", "FULL_BODY_3_DAY_V1")
                    .contains(template.path("code").asText())) {
                return;
            }
            List<String> weeklyPatterns = StreamSupport.stream(template.path("days").spliterator(), false)
                    .flatMap(day -> StreamSupport.stream(day.path("exercises").spliterator(), false))
                    .map(slot -> patterns.get(slot.path("exerciseCode").asText()))
                    .toList();
            assertThat(weeklyPatterns)
                    .as(template.path("code").asText())
                    .contains("ELBOW_FLEXION", "ELBOW_EXTENSION");
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
    void everyP0FrequencyAndEquipmentRangeHasATemplateThatFitsTheFortyFiveMinuteSessionDuration()
            throws IOException {
        JsonNode templates = readValidated("plan-templates-v1.json").path("templates");
        JsonNode rules = readValidated("rule-config-v1.json");
        int secondsPerWorkSet = rules.at("/parameters/duration/secondsPerWorkSet").asInt();
        int secondsPerTransition = rules.at("/parameters/duration/secondsPerExerciseTransition").asInt();
        int selectedSessionMinutes = 45;

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
                                                <= selectedSessionMinutes * 60)))
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
    void contralateralLimbRaiseKeepsAuthoritativeGluteRecoveryAndIsNotUsedInAdjacentDayTemplates()
            throws IOException {
        JsonNode catalog = readValidated("exercises-v1.json");
        JsonNode exercise = StreamSupport.stream(catalog.path("exercises").spliterator(), false)
                .filter(candidate -> "CONTRALATERAL_LIMB_RAISE".equals(candidate.path("code").asText()))
                .findFirst()
                .orElseThrow();

        assertThat(texts(exercise.path("primaryMuscles")))
                .containsExactlyInAnyOrder("BACK", "GLUTES", "SHOULDERS");
        assertThat(catalog.path("metadata").path("sourceReferences"))
                .anyMatch(source -> "ACE_CONTRALATERAL_LIMB_RAISES".equals(source.path("id").asText())
                        && "https://www.acefitness.org/resources/everyone/exercise-library/53/contralateral-limb-raises/"
                                .equals(source.path("url").asText()));

        readValidated("plan-templates-v1.json").path("templates").forEach(template -> {
            if (template.path("code").asText().contains("BODYWEIGHT")
                    && template.path("sessionsPerWeek").asInt() >= 4) {
                template.path("days").forEach(day -> assertThat(day.path("exercises"))
                        .noneMatch(slot -> "CONTRALATERAL_LIMB_RAISE"
                                .equals(slot.path("exerciseCode").asText())));
            }
        });
    }

    @Test
    void reviewedReplacementMatrixHasTwoToFourExactCandidatesOrAnExplicitReviewRequiredGap()
            throws IOException {
        JsonNode exercises = readValidated("exercises-v1.json").path("exercises");
        Map<String, JsonNode> byCode = new java.util.LinkedHashMap<>();
        exercises.forEach(exercise -> byCode.put(exercise.path("code").asText(), exercise));

        byCode.values().stream().filter(exercise -> exercise.path("active").asBoolean()).forEach(source -> {
            String sourceCode = source.path("code").asText();
            List<JsonNode> declared = StreamSupport.stream(
                            source.path("alternatives").spliterator(), false)
                    .toList();
            assertThat(declared).extracting(candidate -> candidate.path("exerciseCode").asText())
                    .as(sourceCode + " declared replacement codes")
                    .doesNotHaveDuplicates()
                    .allMatch(byCode::containsKey);
            assertThat(declared).extracting(candidate -> candidate.path("rank").asInt())
                    .as(sourceCode + " replacement ranks")
                    .doesNotHaveDuplicates();

            List<JsonNode> compatible = declared.stream()
                    .map(candidate -> byCode.get(candidate.path("exerciseCode").asText()))
                    .filter(candidate -> exactReplacementEquivalent(source, candidate))
                    .toList();
            if (REVIEW_REQUIRED_REPLACEMENT_SEMANTICS.contains(sourceCode)) {
                assertThat(compatible).as(sourceCode + " review-required semantic gap").hasSizeLessThan(2);
            } else {
                assertThat(declared).as(sourceCode + " reviewed declarations").hasSizeBetween(2, 4);
                assertThat(compatible).as(sourceCode + " exact semantic candidates").hasSize(declared.size());
            }
        });

        Map<String, Set<String>> equipmentByEnvironment = Map.of(
                "HOME", Set.of(),
                "GYM", Set.of("DUMBBELL", "BENCH", "CABLE", "MACHINE"));
        equipmentByEnvironment.forEach((environment, availableEquipment) -> byCode.values().stream()
                .filter(exercise -> exercise.path("active").asBoolean())
                .filter(exercise -> supports(exercise, availableEquipment))
                .forEach(source -> {
                    String sourceCode = source.path("code").asText();
                    long supportedCandidates = StreamSupport.stream(
                                    source.path("alternatives").spliterator(), false)
                            .map(candidate -> byCode.get(candidate.path("exerciseCode").asText()))
                            .filter(candidate -> candidate != null
                                    && candidate.path("active").asBoolean()
                                    && supports(candidate, availableEquipment)
                                    && exactReplacementEquivalent(source, candidate))
                            .count();
                    if (REVIEW_REQUIRED_ENVIRONMENT_GAPS.get(environment).contains(sourceCode)) {
                        assertThat(supportedCandidates)
                                .as(environment + "/" + sourceCode + " explicit review-required gap")
                                .isLessThan(2);
                    } else {
                        assertThat(supportedCandidates)
                                .as(environment + "/" + sourceCode + " reviewed replacement support")
                                .isBetween(2L, 4L);
                    }
                }));
    }

    private static boolean exactReplacementEquivalent(JsonNode source, JsonNode candidate) {
        return source.path("movementPattern").asText().equals(candidate.path("movementPattern").asText())
                && source.path("difficulty").asText().equals(candidate.path("difficulty").asText())
                && texts(source.path("primaryMuscles")).equals(texts(candidate.path("primaryMuscles")))
                && validLoadMode(source.path("equipment"))
                && validLoadMode(candidate.path("equipment"));
    }

    private static boolean supports(JsonNode exercise, Set<String> availableEquipment) {
        return texts(exercise.path("equipment")).stream()
                .allMatch(equipment -> "BODYWEIGHT".equals(equipment)
                        || availableEquipment.contains(equipment));
    }

    private static boolean validLoadMode(JsonNode equipment) {
        Set<String> values = texts(equipment);
        return !values.isEmpty() && (values.equals(Set.of("BODYWEIGHT")) || !values.contains("BODYWEIGHT"));
    }

    private static Set<String> texts(JsonNode values) {
        Set<String> result = new HashSet<>();
        values.forEach(value -> result.add(value.asText()));
        return Set.copyOf(result);
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
        assertThat(rules.at("/parameters/warmup/knownWorkWeightRatios")).hasSameSizeAs(
                rules.at("/parameters/warmup/rampSetReps"));
        assertThat(texts(rules.at("/parameters/warmup/eligibleLoadedCompoundMovementPatterns")))
                .containsExactlyInAnyOrder(
                        "SQUAT", "HINGE", "HORIZONTAL_PUSH", "HORIZONTAL_PULL",
                        "VERTICAL_PUSH", "VERTICAL_PULL");
    }

    @Test
    void progressionThresholdsAreVersionedConservativeProductRules() throws IOException {
        JsonNode rules = readValidated("rule-config-v1.json");

        assertThat(rules.at("/metadata/version").asText()).isEqualTo("1.6.0");
        assertThat(rules.at("/parameters/progression/longTrainingGapDays").asInt()).isEqualTo(21);
        assertThat(rules.at("/parameters/progression/multipleFailedSetsThreshold").asInt()).isEqualTo(2);
    }

    @Test
    void sessionCompositionDefinesTheFortyFiveMinuteTargetAndCompleteWarmupBudget()
            throws IOException {
        JsonNode rules = readValidated("rule-config-v1.json");
        JsonNode composition = readValidated("rule-config-v1.json").at("/parameters/sessionComposition");
        assertThat(composition.at("/targetExercisesByMinutes/0/sessionMinutes").asInt()).isEqualTo(45);
        assertThat(composition.at("/targetExercisesByMinutes/0/minimumExercises").asInt()).isEqualTo(4);
        assertThat(composition.at("/targetExercisesByMinutes/0/maximumExercises").asInt()).isEqualTo(5);
        assertThat(composition.has("experiencedExerciseBonus")).isFalse();
        assertThat(composition.path("accessoryWorkSets").asInt()).isEqualTo(2);
        assertThat(rules.at("/parameters/balance/maximumMovementPatternOccurrencesPerSession").asInt())
                .isEqualTo(1);
        assertThat(rules.at("/parameters/duration/generalWarmupSeconds").asInt()).isEqualTo(180);
        assertThat(rules.at("/parameters/duration/rampWarmupSetsPerSession").asInt()).isEqualTo(2);
        assertThat(rules.at("/parameters/duration/rampWarmupSetsPerSession").asInt())
                .isLessThanOrEqualTo(rules.at("/parameters/warmup/maximumRampSets").asInt());
    }

    @Test
    void weeklyMovementTargetsKeepLoadedFullBodyPlansBalancedAndIncludeDirectArms()
            throws IOException {
        JsonNode targets = readValidated("rule-config-v1.json")
                .at("/parameters/balance/weeklyMovementPatternTargets");

        assertThat(targets).hasSize(2);
        JsonNode threeDay = targets.get(1);
        assertThat(threeDay.path("sessionsPerWeek").asInt()).isEqualTo(3);
        Map<String, JsonNode> byPattern = new java.util.LinkedHashMap<>();
        threeDay.path("targets").forEach(target ->
                byPattern.put(target.path("movementPattern").asText(), target));
        assertThat(byPattern.keySet()).contains(
                "HORIZONTAL_PUSH", "VERTICAL_PUSH", "HORIZONTAL_PULL", "VERTICAL_PULL",
                "ELBOW_FLEXION", "ELBOW_EXTENSION");
        assertThat(byPattern.get("HORIZONTAL_PUSH").path("minimumSessions").asInt()).isEqualTo(2);
        assertThat(byPattern.get("VERTICAL_PUSH").path("maximumSessions").asInt()).isEqualTo(1);
        assertThat(byPattern.get("ELBOW_FLEXION").path("maximumSessions").asInt()).isEqualTo(2);
        assertThat(byPattern.get("ELBOW_EXTENSION").path("maximumSessions").asInt()).isEqualTo(2);
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
        Set<String> configuredGoals = new HashSet<>();
        rules.at("/parameters/goalPrescriptions").forEach(goalPrescription -> {
            configuredGoals.add(goalPrescription.path("goal").asText());
            assertThat(goalPrescription.path("workSets").asInt())
                    .isBetween(
                            rules.at("/parameters/prescription/minimumWorkSets").asInt(),
                            rules.at("/parameters/prescription/maximumWorkSets").asInt());
            assertThat(goalPrescription.path("repMin").asInt())
                    .isGreaterThanOrEqualTo(rules.at("/parameters/prescription/minimumReps").asInt())
                    .isLessThanOrEqualTo(goalPrescription.path("repMax").asInt());
            assertThat(goalPrescription.path("repMax").asInt())
                    .isLessThanOrEqualTo(rules.at("/parameters/prescription/maximumReps").asInt());
            assertThat(goalPrescription.path("restSeconds").asInt())
                    .isBetween(
                            rules.at("/parameters/rest/minimumSeconds").asInt(),
                            rules.at("/parameters/rest/maximumSeconds").asInt());
        });
        assertThat(configuredGoals)
                .containsExactlyInAnyOrder("STRENGTH", "HYPERTROPHY", "GENERAL_FITNESS");

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
