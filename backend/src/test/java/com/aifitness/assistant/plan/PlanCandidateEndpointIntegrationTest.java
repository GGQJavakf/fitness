package com.aifitness.assistant.plan;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.aifitness.assistant.rules.domain.PlanRulePolicy;
import com.aifitness.assistant.rules.infrastructure.ClasspathPlanRulePolicyLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.StreamSupport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlanCandidateEndpointIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void requiresAuthenticationForCandidateGeneration() throws Exception {
        mvc.perform(post("/api/v1/plans/candidates")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":0,\"lockedFields\":{}}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void generatesAndValidatesCompleteRuleOwnedCandidateWithAiDisabled() throws Exception {
        String token = login();
        configureProfile(token);
        configureEquipment(token);

        String response = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andExpect(jsonPath("$.data.candidate.plan.days.length()").value(3))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].estimatedMinutesMin").doesNotExist())
                .andExpect(jsonPath("$.data.candidate.plan.days[0].estimatedMinutesMax").doesNotExist())
                .andExpect(jsonPath("$.data.candidate.plan.days[0].exercises[0].executionOrder").doesNotExist())
                .andExpect(jsonPath("$.data.candidate.plan.days[*].exercises[*].weightStatus")
                        .value(org.hamcrest.Matchers.hasItem("NEEDS_CALIBRATION")))
                .andExpect(jsonPath("$.data.candidate.ruleReference.ruleVersion").value("1.6.0"))
                .andExpect(jsonPath("$.data.candidate.explanationStatus").value("DEGRADED"))
                .andExpect(jsonPath("$.data.candidate.explanation").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        JsonNode candidate = objectMapper.readTree(response).at("/data/candidate");
        mvc.perform(post("/api/v1/plans/validate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.validationIssues[0].reasonCode")
                        .value("INITIAL_WEIGHT_NEEDS_CALIBRATION"));
    }

    @Test
    void validatesAnOlderPlanAgainstCurrentRulesWithAnExplicitUpgradeWarning() throws Exception {
        String token = login();
        configureProfile(token);
        configureEquipment(token);
        JsonNode candidate = generateCandidate(token);
        ((com.fasterxml.jackson.databind.node.ObjectNode) candidate.path("ruleReference"))
                .put("ruleVersion", "1.1.0")
                .put("templateVersion", "1.1.0");

        mvc.perform(post("/api/v1/plans/validate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.validationIssues[*].reasonCode")
                        .value(org.hamcrest.Matchers.hasItem("RULE_REFERENCE_UPGRADED")));
    }

    @Test
    void generatesRuleValidCandidateForEverySupportedGymFrequency() {
        IntStream.rangeClosed(2, 6).forEach(frequency -> {
            try {
                String token = login();
                configureProfile(token, frequency);
                configureEquipment(token);

                String response = mvc.perform(post("/api/v1/plans/candidates")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();
                JsonNode data = objectMapper.readTree(response).path("data");
                org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                        .isEqualTo("CANDIDATE_READY");
                org.assertj.core.api.Assertions.assertThat(data.at("/candidate/plan/days").size())
                        .isEqualTo(frequency);
                org.assertj.core.api.Assertions.assertThat(data.at("/candidate/validationIssues"))
                        .noneMatch(issue -> "ERROR".equals(issue.path("severity").asText())
                                || "RECOVERY_WINDOW_TOO_SHORT".equals(issue.path("reasonCode").asText()));
            } catch (Exception exception) {
                throw new AssertionError("candidate generation failed for weekly frequency " + frequency, exception);
            }
        });
    }

    @Test
    void generatesTheExplicitProfessionalTwoThreeAndFiveDaySplits() throws Exception {
        int[] frequencies = {2, 4, 3, 6, 5};
        String[] splits = {
            "UPPER_LOWER", "UPPER_LOWER", "PUSH_PULL_LEGS", "PUSH_PULL_LEGS", "BODY_PART_FIVE_DAY"
        };
        String[] templates = {
            "UPPER_LOWER_2_DAY_V1",
            "UPPER_LOWER_4_DAY_V1",
            "PUSH_PULL_LEGS_3_DAY_V1",
            "PUSH_PULL_LEGS_6_DAY_V1",
            "BODY_PART_5_DAY_V1"
        };

        for (int index = 0; index < frequencies.length; index++) {
            String token = login();
            configureProfile(token, frequencies[index], 45, "HYPERTROPHY", "INTERMEDIATE", "GYM");
            configureEquipment(token);

            JsonNode data = objectMapper.readTree(mvc.perform(post("/api/v1/plans/candidates")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"profileVersion\":1,\"trainingSplit\":\""
                                    + splits[index] + "\",\"lockedFields\":{}}"))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString()).path("data");

            org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                    .as(splits[index] + " at frequency " + frequencies[index])
                    .isEqualTo("CANDIDATE_READY");
            org.assertj.core.api.Assertions.assertThat(data.at("/candidate/plan/templateCode").asText())
                    .isEqualTo(templates[index]);
            org.assertj.core.api.Assertions.assertThat(data.at("/candidate/plan/days"))
                    .hasSize(frequencies[index])
                    .allSatisfy(day -> org.assertj.core.api.Assertions.assertThat(day.path("exercises").size())
                            .isBetween(4, 5));

            String candidateId = data.at("/candidate/candidateId").asText();
            String activated = mvc.perform(post("/api/v1/plans")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"candidateId\":\"" + candidateId + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.data.activeVersion.plan.trainingSplit").value(splits[index]))
                    .andReturn().getResponse().getContentAsString();
            String planId = objectMapper.readTree(activated).at("/data/planId").asText();
            mvc.perform(get("/api/v1/plans/active")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.planId").value(planId))
                    .andExpect(jsonPath("$.data.activeVersion.plan.templateCode").value(templates[index]))
                    .andExpect(jsonPath("$.data.activeVersion.plan.trainingSplit").value(splits[index]));
        }

        String fiveDayToken = login();
        configureProfile(fiveDayToken, 5, 45, "HYPERTROPHY", "ADVANCED", "GYM");
        configureEquipment(fiveDayToken);
        JsonNode fiveDay = generateCandidate(fiveDayToken, "BODY_PART_FIVE_DAY").path("plan");
        org.assertj.core.api.Assertions.assertThat(exerciseCodes(fiveDay, "ARMS_A"))
                .contains(
                        "DUMBBELL_BICEPS_CURL",
                        "DUMBBELL_HAMMER_CURL",
                        "CABLE_TRICEPS_PUSHDOWN",
                        "DUMBBELL_OVERHEAD_TRICEPS_EXTENSION");
        org.assertj.core.api.Assertions.assertThat(exerciseCodes(fiveDay, "SHOULDERS_A"))
                .containsOnlyOnce("DUMBBELL_OVERHEAD_PRESS");
    }

    @Test
    void listsSelectsAndActivatesTheFixedFiveDayHypertrophyPresetWithoutRewritingItsPrescription()
            throws Exception {
        String token = login();
        configureProfile(token, 5, 45, "HYPERTROPHY", "INTERMEDIATE", "GYM");
        configureEquipment(token);

        mvc.perform(get("/api/v1/plans/presets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].code")
                        .value("PERSONAL_5_DAY_HYPERTROPHY_V1"))
                .andExpect(jsonPath("$.data.items[0].days.length()").value(5));

        String response = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVersion":1,"lockedFields":{},
                                 "presetCode":"PERSONAL_5_DAY_HYPERTROPHY_V1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andExpect(jsonPath("$.data.candidate.generationSource").value("SYSTEM_PRESET"))
                .andExpect(jsonPath("$.data.candidate.plan.presetVersion").value("1.0.0"))
                .andExpect(jsonPath("$.data.candidate.plan.days.length()").value(5))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].estimatedMinutesMin").value(44))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].warmup.length()").value(5))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].exercises[0].workSets").value(4))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].exercises[0].repMin").value(6))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].exercises[4].repMax").value(20))
                .andExpect(jsonPath("$.data.candidate.plan.days[0].exercises[4].executionGroup")
                        .value("MONDAY_ARMS"))
                .andExpect(jsonPath("$.data.candidate.plan.days[1].exercises[2].perSide").value(true))
                .andReturn().getResponse().getContentAsString();

        JsonNode candidate = objectMapper.readTree(response).at("/data/candidate");
        mvc.perform(post("/api/v1/plans/validate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetValidationRequest(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true));

        String candidateId = candidate.path("candidateId").asText();
        mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activeVersion.plan.presetCode")
                        .value("PERSONAL_5_DAY_HYPERTROPHY_V1"))
                .andExpect(jsonPath("$.data.activeVersion.plan.days[3].exercises[5].executionOrder")
                        .value(2));
    }

    @Test
    void rejectsUnsafePrescriptionWhenValidatingOrEditingASystemPreset() throws Exception {
        String token = login();
        configureProfile(token, 5, 45, "HYPERTROPHY", "INTERMEDIATE", "GYM");
        configureEquipment(token);

        JsonNode candidate = generatePresetCandidate(token);
        assertPresetPrescriptionRejected(
                token, candidate, exercise -> exercise.put("workSets", 0),
                "WORK_SETS_OUT_OF_RANGE", "/days/0/exercises/0/workSets");
        assertPresetPrescriptionRejected(
                token, candidate, exercise -> exercise.put("workSets", 7),
                "WORK_SETS_OUT_OF_RANGE", "/days/0/exercises/0/workSets");
        assertPresetPrescriptionRejected(
                token, candidate, exercise -> exercise.put("repMin", 0),
                "REP_RANGE_OUT_OF_RANGE", "/days/0/exercises/0/repRange");
        assertPresetPrescriptionRejected(
                token, candidate, exercise -> exercise.put("repMax", 31),
                "REP_RANGE_OUT_OF_RANGE", "/days/0/exercises/0/repRange");
        assertPresetPrescriptionRejected(
                token, candidate, exercise -> exercise.put("repMin", exercise.path("repMax").asInt() + 1),
                "REP_RANGE_OUT_OF_RANGE", "/days/0/exercises/0/repRange");
        assertPresetPrescriptionRejected(
                token, candidate, exercise -> exercise.put("restSeconds", 301),
                "REST_OUT_OF_RANGE", "/days/0/exercises/0/restSeconds");

        JsonNode created = objectMapper.readTree(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidate.path("candidateId").asText() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        ObjectNode unsafePlan = candidate.path("plan").deepCopy();
        ((ObjectNode) unsafePlan.at("/days/0/exercises/0")).put("workSets", 0);
        mvc.perform(post("/api/v1/plans/{planId}/versions", created.at("/data/planId").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "baseVersionNumber", 1,
                                "plan", unsafePlan,
                                "locks", Map.of()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.data.validationIssues[*].severity")
                        .value(org.hamcrest.Matchers.hasItem("ERROR")))
                .andExpect(jsonPath("$.data.validationIssues[*].reasonCode")
                        .value(org.hamcrest.Matchers.hasItem("WORK_SETS_OUT_OF_RANGE")));
    }

    @Test
    void rejectsAnExplicitSplitThatDoesNotMatchTheSelectedFrequency() throws Exception {
        String token = login();
        configureProfile(token, 3);
        configureEquipment(token);

        mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"trainingSplit\":\"UPPER_LOWER\",\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"))
                .andExpect(jsonPath("$.data.validationIssues[0].reasonCode")
                        .value("SPLIT_FREQUENCY_MISMATCH"));
    }

    @Test
    void generatesBodyweightCandidateWhenSafeAndReturnsTypedCapacityWhenDirectArmWorkIsUnavailable() {
        IntStream.rangeClosed(2, 6).forEach(frequency -> {
            try {
                String token = login();
                configureProfile(token, frequency);

                String response = mvc.perform(post("/api/v1/plans/candidates")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                        .andExpect(status().isOk())
                        .andReturn().getResponse().getContentAsString();

                JsonNode data = objectMapper.readTree(response).path("data");
                if (frequency >= 4) {
                    org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                            .as("bodyweight frequency %s must not fake a professional upper-body split", frequency)
                            .isEqualTo("NO_CANDIDATE");
                    org.assertj.core.api.Assertions.assertThat(data.path("validationIssues"))
                            .anyMatch(issue -> "INSUFFICIENT_ELIGIBLE_EXERCISES"
                                    .equals(issue.path("reasonCode").asText()));
                    return;
                }
                org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                        .isEqualTo("CANDIDATE_READY");
                JsonNode candidate = data.path("candidate");
                org.assertj.core.api.Assertions.assertThat(candidate.at("/plan/templateCode").asText())
                        .contains("BODYWEIGHT");
                org.assertj.core.api.Assertions.assertThat(candidate.at("/plan/days")).hasSize(frequency);
                JsonNode exercises = candidate
                        .at("/plan/days").findValues("exercises").stream()
                        .reduce(objectMapper.createArrayNode(),
                                (all, dayExercises) -> {
                                    dayExercises.forEach(all::add);
                                    return all;
                                },
                                 (left, right) -> left.addAll(right));
                org.assertj.core.api.Assertions.assertThat(exercises)
                        .allMatch(exercise -> "BODYWEIGHT".equals(exercise.path("weightStatus").asText()));
                org.assertj.core.api.Assertions.assertThat(candidate.path("validationIssues"))
                        .noneMatch(issue -> "RECOVERY_WINDOW_TOO_SHORT"
                                .equals(issue.path("reasonCode").asText()));
            } catch (Exception exception) {
                throw new AssertionError(
                        "bodyweight candidate generation failed for weekly frequency " + frequency, exception);
            }
        });
    }

    @Test
    void everyTemplateDrivingCombinationGeneratesAValidCandidateOrATypedCapacityDiagnostic()
            throws Exception {
        String[] goals = {"GENERAL_FITNESS", "STRENGTH", "HYPERTROPHY", "FAT_LOSS"};
        String[] experiences = {"BEGINNER", "INTERMEDIATE", "ADVANCED"};
        String[] locations = {"HOME", "GYM", "OTHER"};
        int[] sessionMinutes = {30, 45, 60, 75, 90};
        int combinationIndex = 0;

        for (int frequency = 2; frequency <= 6; frequency++) {
            for (int minutes : sessionMinutes) {
                for (boolean gymEquipment : new boolean[] {false, true}) {
                    String token = login();
                    configureProfile(
                            token,
                            frequency,
                            minutes,
                            goals[combinationIndex % goals.length],
                            experiences[combinationIndex % experiences.length],
                            locations[combinationIndex % locations.length]);
                    if (gymEquipment) {
                        configureEquipment(token);
                    }

                    String response = mvc.perform(post("/api/v1/plans/candidates")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    JsonNode data = objectMapper.readTree(response).path("data");
                    String combination = "frequency=" + frequency
                            + ", minutes=" + minutes
                            + ", equipment=" + (gymEquipment ? "GYM" : "BODYWEIGHT");

                    if ("NO_CANDIDATE".equals(data.path("status").asText())) {
                        org.assertj.core.api.Assertions.assertThat(data.path("validationIssues"))
                                .as(combination)
                                .anyMatch(issue -> java.util.Set.of(
                                                "INSUFFICIENT_ELIGIBLE_EXERCISES",
                                                "RECOVERY_WINDOW_TOO_SHORT")
                                        .contains(issue.path("reasonCode").asText()));
                        combinationIndex++;
                        continue;
                    }
                    org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                            .as(combination)
                            .isEqualTo("CANDIDATE_READY");
                    org.assertj.core.api.Assertions.assertThat(data.at("/candidate/validationIssues"))
                            .as(combination)
                            .noneMatch(issue -> "ERROR".equals(issue.path("severity").asText())
                                    || "RECOVERY_WINDOW_TOO_SHORT".equals(issue.path("reasonCode").asText()));
                    org.assertj.core.api.Assertions.assertThat(
                                    data.at("/candidate/plan/templateCode").asText().contains("BODYWEIGHT"))
                            .as(combination)
                            .isEqualTo(!gymEquipment);
                    combinationIndex++;
                }
            }
        }
    }

    @Test
    void fortyFiveMinuteHomeAndGymMatrixComposesFourOrFiveSafeExercisesForEverySupportedFrequency()
            throws Exception {
        PlanRulePolicy policy = ClasspathPlanRulePolicyLoader.load(objectMapper);
        StringBuilder matrixEvidence = new StringBuilder();
        for (String location : new String[] {"HOME", "GYM"}) {
            for (int frequency = 2; frequency <= 6; frequency++) {
                for (String goal : new String[] {"GENERAL_FITNESS", "STRENGTH", "HYPERTROPHY", "FAT_LOSS"}) {
                    String token = login();
                    configureProfile(token, frequency, 45, goal, "BEGINNER", location);
                    if ("GYM".equals(location)) {
                        configureEquipment(token);
                    }

                    String response = mvc.perform(post("/api/v1/plans/candidates")
                                    .header("Authorization", "Bearer " + token)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                            .andExpect(status().isOk())
                            .andReturn().getResponse().getContentAsString();
                    JsonNode data = objectMapper.readTree(response).path("data");
                    String combination = "location=" + location + ", frequency=" + frequency + ", goal=" + goal;

                    boolean supported = "GYM".equals(location) || frequency <= 3;
                    org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                            .as(combination)
                            .isEqualTo(supported ? "CANDIDATE_READY" : "NO_CANDIDATE");
                    if (!supported) {
                        org.assertj.core.api.Assertions.assertThat(data.path("validationIssues"))
                                .as(combination)
                                .anyMatch(issue -> "INSUFFICIENT_ELIGIBLE_EXERCISES"
                                        .equals(issue.path("reasonCode").asText()));
                        matrixEvidence.append(combination)
                                .append(" status=NO_CANDIDATE reason=INSUFFICIENT_ELIGIBLE_EXERCISES")
                                .append(System.lineSeparator());
                        continue;
                    }
                    JsonNode days = data.at("/candidate/plan/days");
                    org.assertj.core.api.Assertions.assertThat(
                                    data.at("/candidate/plan/templateCode").asText().contains("BODYWEIGHT"))
                            .as(combination + " equipment-specific template")
                            .isEqualTo("HOME".equals(location));
                    org.assertj.core.api.Assertions.assertThat(days)
                            .as(combination)
                            .allSatisfy(day -> {
                                org.assertj.core.api.Assertions.assertThat(day.path("exercises").size())
                                        .isBetween(4, 5);
                                org.assertj.core.api.Assertions.assertThat(estimatedSessionSeconds(day, policy))
                                        .isLessThanOrEqualTo(45 * 60);
                            });
                    matrixEvidence.append(combination).append(" status=CANDIDATE_READY days=");
                    for (JsonNode day : days) {
                        matrixEvidence.append(day.path("code").asText())
                                .append(':')
                                .append(day.path("exercises").size())
                                .append('@')
                                .append(estimatedSessionSeconds(day, policy))
                                .append("s ");
                    }
                    matrixEvidence.append(System.lineSeparator());
                }
            }
        }
        if (Boolean.getBoolean("fitness.matrix.report")) {
            System.out.print(matrixEvidence);
        }
    }

    @Test
    void fallbackTemplatesUseDurationAsBudgetAndGoalsChangePrescription() throws Exception {
        String thirtyMinuteToken = login();
        configureProfile(thirtyMinuteToken, 3, 30, "GENERAL_FITNESS", "BEGINNER", "GYM");
        configureEquipment(thirtyMinuteToken);
        JsonNode thirtyMinutePlan = generateCandidate(thirtyMinuteToken).path("plan");

        String fortyFiveMinuteToken = login();
        configureProfile(fortyFiveMinuteToken, 3, 45, "GENERAL_FITNESS", "BEGINNER", "GYM");
        configureEquipment(fortyFiveMinuteToken);
        JsonNode fortyFiveMinutePlan = generateCandidate(fortyFiveMinuteToken).path("plan");

        int thirtyMinuteExerciseCount =
                thirtyMinutePlan.at("/days/0/exercises").size();
        int fortyFiveMinuteExerciseCount =
                fortyFiveMinutePlan.at("/days/0/exercises").size();
        org.assertj.core.api.Assertions.assertThat(thirtyMinuteExerciseCount)
                .isPositive()
                .isLessThan(fortyFiveMinuteExerciseCount);
        org.assertj.core.api.Assertions.assertThat(fortyFiveMinuteExerciseCount)
                .isBetween(4, 5);
        org.assertj.core.api.Assertions.assertThat(fortyFiveMinutePlan.at("/days"))
                .allSatisfy(day -> org.assertj.core.api.Assertions.assertThat(day.path("exercises").size())
                        .isBetween(4, 5));
        org.assertj.core.api.Assertions.assertThat(thirtyMinutePlan).isNotEqualTo(fortyFiveMinutePlan);

        Map<String, Integer> expectedRepMinimums =
                Map.of("STRENGTH", 5, "HYPERTROPHY", 8, "FAT_LOSS", 10, "GENERAL_FITNESS", 10);
        Map<String, Integer> expectedRestSeconds =
                Map.of("STRENGTH", 120, "HYPERTROPHY", 90, "FAT_LOSS", 75, "GENERAL_FITNESS", 75);
        for (String goal : expectedRepMinimums.keySet()) {
            String token = login();
            configureProfile(token, 3, 45, goal, "BEGINNER", "GYM");
            configureEquipment(token);
            JsonNode firstExercise = generateCandidate(token).at("/plan/days/0/exercises/0");

            org.assertj.core.api.Assertions.assertThat(firstExercise.path("repMin").asInt())
                    .as(goal)
                    .isEqualTo(expectedRepMinimums.get(goal));
            org.assertj.core.api.Assertions.assertThat(firstExercise.path("restSeconds").asInt())
                    .as(goal)
                    .isEqualTo(expectedRestSeconds.get(goal));
        }
    }

    @Test
    void candidateLockSurvivesInitialVersionAndRebalancePreview() throws Exception {
        String token = login();
        configureProfile(token);
        configureEquipment(token);
        String path = "/days/DAY_A/exercises/GOBLET_SQUAT/restSeconds";

        JsonNode candidate = objectMapper.readTree(mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{\"" + path + "\":180}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.candidate.plan.locks['" + path + "']")
                        .value("USER_LOCKED"))
                .andReturn().getResponse().getContentAsString()).at("/data/candidate");

        JsonNode unlockedCandidate = objectMapper.readTree(mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/candidate");
        org.assertj.core.api.Assertions.assertThat(unlockedCandidate.path("candidateId").asText())
                .isNotEqualTo(candidate.path("candidateId").asText());

        JsonNode active = objectMapper.readTree(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidate.path("candidateId").asText() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activeVersion.plan.locks['" + path + "']")
                        .value("USER_LOCKED"))
                .andReturn().getResponse().getContentAsString()).at("/data");

        JsonNode proposed = candidate.path("plan").deepCopy();
        boolean changedLockedExercise = false;
        for (JsonNode exercise : proposed.at("/days/0/exercises")) {
            if ("GOBLET_SQUAT".equals(exercise.path("exerciseCode").asText())) {
                ((com.fasterxml.jackson.databind.node.ObjectNode) exercise).put("restSeconds", 240);
                changedLockedExercise = true;
            }
        }
        org.assertj.core.api.Assertions.assertThat(changedLockedExercise).isTrue();
        ((com.fasterxml.jackson.databind.node.ObjectNode) proposed).remove("locks");
        String request = objectMapper.writeValueAsString(Map.of(
                "baseVersionNumber", 1, "plan", proposed, "locks", Map.of()));
        String rebalanced = mvc.perform(post("/api/v1/plans/{planId}/rebalance", active.path("planId").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.locks['" + path + "']").value("USER_LOCKED"))
                .andReturn().getResponse().getContentAsString();
        JsonNode rebalancedExercises = objectMapper.readTree(rebalanced).at("/data/plan/days/0/exercises");
        org.assertj.core.api.Assertions.assertThat(rebalancedExercises)
                .filteredOn(exercise -> "GOBLET_SQUAT".equals(exercise.path("exerciseCode").asText()))
                .singleElement()
                .satisfies(exercise -> org.assertj.core.api.Assertions.assertThat(
                                exercise.path("restSeconds").asInt())
                        .isEqualTo(180));
    }

    @Test
    void rejectsStaleProfileVersionWithoutGeneratingCandidate() throws Exception {
        String token = login();
        configureProfile(token);

        mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":0,\"lockedFields\":{}}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"));
    }

    @Test
    void rejectsOversizedLockedFieldCollection() throws Exception {
        String token = login();
        configureProfile(token);
        StringBuilder locks = new StringBuilder();
        for (int index = 0; index < 101; index++) {
            if (!locks.isEmpty()) locks.append(',');
            locks.append("\"/days/DAY_A/exercises/EX_").append(index)
                    .append("/restSeconds\":120");
        }

        mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{" + locks + "}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void rejectsMalformedValidationDraftWithoutServerError() throws Exception {
        String token = login();
        configureProfile(token);

        mvc.perform(post("/api/v1/plans/validate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "candidateId":"candidate-test",
                                  "plan":{"templateCode":"FULL_BODY_3D","name":"test","days":[
                                    {"code":"DAY_A","name":"day","exercises":null}
                                  ],"locks":{}},
                                  "validationIssues":[],
                                  "ruleReference":{"ruleVersion":"1.1.0","templateVersion":"1.0.0","contentVersion":"1.0.0"},
                                  "lockedFieldOutcomes":{},
                                  "explanationStatus":"DEGRADED",
                                  "explanation":"template explanation",
                                  "expiresAt":"2030-01-01T00:00:00Z"
                                }
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void excludesUserRejectedExercisesFromGenerationAndValidation() throws Exception {
        String token = login();
        configureProfile(token);
        configureEquipment(token);

        String generated = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode candidate = objectMapper.readTree(generated).at("/data/candidate");
        String exerciseId = objectMapper.readTree(mvc.perform(get("/api/v1/exercises/GOBLET_SQUAT")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .at("/data/id").asText();
        mvc.perform(put("/api/v1/profile/preferences")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[{\"exerciseId\":\"" + exerciseId
                                + "\",\"preferenceType\":\"EXCLUDED\"}],\"expectedVersion\":0}"))
                .andExpect(status().isOk());

        mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andExpect(jsonPath("$.data.candidate.plan.days[*].exercises[*].exerciseCode")
                        .value(org.hamcrest.Matchers.not(org.hamcrest.Matchers.hasItem("GOBLET_SQUAT"))));
        mvc.perform(post("/api/v1/plans/validate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validationRequest(candidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.validationIssues[*].reasonCode")
                        .value(org.hamcrest.Matchers.hasItem("EXERCISE_NOT_ELIGIBLE")));
    }

    private void configureProfile(String token) throws Exception {
        configureProfile(token, 3);
    }

    private void configureProfile(String token, int weeklyFrequency) throws Exception {
        configureProfile(token, weeklyFrequency, 60, "GENERAL_FITNESS", "BEGINNER", "GYM");
    }

    private void configureProfile(
            String token,
            int weeklyFrequency,
            int sessionMinutes,
            String goal,
            String experience,
            String location) throws Exception {
        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"%s","goal":"%s",
                                 "weeklyFrequency":%d,"sessionMinutes":%d,"location":"%s",
                                 "expectedVersion":0}
                                """.formatted(
                                        experience, goal, weeklyFrequency, sessionMinutes, location)))
                .andExpect(status().isOk());
    }

    private String validationRequest(JsonNode candidate) throws Exception {
        JsonNode plan = candidate.path("plan");
        return objectMapper.writeValueAsString(Map.of(
                "plan", Map.of(
                        "templateCode", plan.path("templateCode"),
                        "name", plan.path("name"),
                        "days", plan.path("days")),
                "ruleReference", candidate.path("ruleReference")));
    }

    private String presetValidationRequest(JsonNode candidate) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "plan", candidate.path("plan"),
                "ruleReference", candidate.path("ruleReference")));
    }

    private JsonNode generatePresetCandidate(String token) throws Exception {
        String response = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"profileVersion":1,"lockedFields":{},
                                 "presetCode":"PERSONAL_5_DAY_HYPERTROPHY_V1"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/candidate");
    }

    private void assertPresetPrescriptionRejected(
            String token,
            JsonNode candidate,
            Consumer<ObjectNode> mutation,
            String reasonCode,
            String fieldPath) throws Exception {
        ObjectNode unsafeCandidate = candidate.deepCopy();
        mutation.accept((ObjectNode) unsafeCandidate.at("/plan/days/0/exercises/0"));
        mvc.perform(post("/api/v1/plans/validate")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(presetValidationRequest(unsafeCandidate)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andExpect(jsonPath("$.data.validationIssues[*].severity")
                        .value(org.hamcrest.Matchers.hasItem("ERROR")))
                .andExpect(jsonPath("$.data.validationIssues[*].reasonCode")
                        .value(org.hamcrest.Matchers.hasItem(reasonCode)))
                .andExpect(jsonPath("$.data.validationIssues[*].fieldPath")
                        .value(org.hamcrest.Matchers.hasItem(fieldPath)));
    }

    private JsonNode generateCandidate(String token) throws Exception {
        String response = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/candidate");
    }

    private JsonNode generateCandidate(String token, String trainingSplit) throws Exception {
        String response = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"trainingSplit\":\""
                                + trainingSplit + "\",\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/candidate");
    }

    private static List<String> exerciseCodes(JsonNode plan, String dayCode) {
        return StreamSupport.stream(plan.path("days").spliterator(), false)
                .filter(day -> dayCode.equals(day.path("code").asText()))
                .findFirst()
                .map(day -> StreamSupport.stream(day.path("exercises").spliterator(), false)
                        .map(exercise -> exercise.path("exerciseCode").asText())
                        .toList())
                .orElseThrow(() -> new AssertionError("missing training day " + dayCode));
    }

    private static int estimatedSessionSeconds(JsonNode day, PlanRulePolicy policy) {
        boolean loadedExercisePresent = StreamSupport.stream(
                        day.path("exercises").spliterator(), false)
                .anyMatch(exercise -> !"BODYWEIGHT".equals(exercise.path("weightStatus").asText()));
        return policy.duration().sessionWarmupSeconds(loadedExercisePresent)
                + StreamSupport.stream(day.path("exercises").spliterator(), false)
                        .mapToInt(exercise -> exercise.path("workSets").asInt()
                                * (policy.duration().secondsPerWorkSet()
                                        + exercise.path("restSeconds").asInt())
                                + policy.duration().secondsPerExerciseTransition())
                        .sum();
    }

    private void configureEquipment(String token) throws Exception {
        StringBuilder items = new StringBuilder();
        for (String type : new String[] {"DUMBBELL", "BENCH", "CABLE", "MACHINE"}) {
            if (!items.isEmpty()) items.append(',');
            items.append("""
                    {"clientEquipmentKey":"%s","equipmentType":"%s",
                     "minIncrement":{"value":1,"unit":"KG"},
                     "availableLevels":[{"value":1,"unit":"KG"}]}
                    """.formatted(UUID.randomUUID(), type));
        }
        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "],\"expectedVersion\":0}"))
                .andExpect(status().isOk());
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"plan-test-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(response).at("/data/accessToken").asText();
    }
}
