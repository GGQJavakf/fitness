package com.aifitness.assistant.plan;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
                .andExpect(jsonPath("$.data.candidate.plan.days[*].exercises[*].weightStatus")
                        .value(org.hamcrest.Matchers.hasItem("NEEDS_CALIBRATION")))
                .andExpect(jsonPath("$.data.candidate.ruleReference.ruleVersion").value("1.2.0"))
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
    void generatesRuleValidCandidateForEveryP0WeeklyFrequency() {
        IntStream.rangeClosed(2, 6).forEach(frequency -> {
            try {
                String token = login();
                configureProfile(token, frequency);
                configureEquipment(token);

                mvc.perform(post("/api/v1/plans/candidates")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                        .andExpect(jsonPath("$.data.candidate.plan.days.length()").value(frequency));
            } catch (Exception exception) {
                throw new AssertionError("candidate generation failed for weekly frequency " + frequency, exception);
            }
        });
    }

    @Test
    void generatesBodyweightCandidateForEveryP0WeeklyFrequencyWithoutEquipment() {
        IntStream.rangeClosed(2, 6).forEach(frequency -> {
            try {
                String token = login();
                configureProfile(token, frequency);

                String response = mvc.perform(post("/api/v1/plans/candidates")
                                .header("Authorization", "Bearer " + token)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                        .andExpect(jsonPath("$.data.candidate.plan.templateCode")
                                .value(org.hamcrest.Matchers.containsString("BODYWEIGHT")))
                        .andExpect(jsonPath("$.data.candidate.plan.days.length()").value(frequency))
                        .andReturn().getResponse().getContentAsString();

                JsonNode candidate = objectMapper.readTree(response).at("/data/candidate");
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
                if (frequency == 5) {
                    org.assertj.core.api.Assertions.assertThat(candidate.path("validationIssues"))
                            .anyMatch(issue -> "RECOVERY_WINDOW_TOO_SHORT"
                                    .equals(issue.path("reasonCode").asText()));
                } else {
                    org.assertj.core.api.Assertions.assertThat(candidate.path("validationIssues"))
                            .noneMatch(issue -> "RECOVERY_WINDOW_TOO_SHORT"
                                    .equals(issue.path("reasonCode").asText()));
                }
            } catch (Exception exception) {
                throw new AssertionError(
                        "bodyweight candidate generation failed for weekly frequency " + frequency, exception);
            }
        });
    }

    @Test
    void everyTemplateDrivingCombinationGeneratesAValidCandidateAndAcceptsAllOtherOptionValues()
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

                    org.assertj.core.api.Assertions.assertThat(data.path("status").asText())
                            .as(combination)
                            .isEqualTo("CANDIDATE_READY");
                    org.assertj.core.api.Assertions.assertThat(data.at("/candidate/validationIssues"))
                            .as(combination)
                            .noneMatch(issue -> "ERROR".equals(issue.path("severity").asText()));
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
                .isLessThanOrEqualTo(8);
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
