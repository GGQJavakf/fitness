package com.aifitness.assistant.plan;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.UUID;
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
                .andExpect(jsonPath("$.data.candidate.plan.days[0].exercises[0].weightStatus")
                        .value("NEEDS_CALIBRATION"))
                .andExpect(jsonPath("$.data.candidate.ruleReference.ruleVersion").value("1.1.0"))
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
        ((com.fasterxml.jackson.databind.node.ObjectNode) proposed.at("/days/0/exercises/0"))
                .put("restSeconds", 240);
        ((com.fasterxml.jackson.databind.node.ObjectNode) proposed).remove("locks");
        String request = objectMapper.writeValueAsString(Map.of(
                "baseVersionNumber", 1, "plan", proposed, "locks", Map.of()));
        mvc.perform(post("/api/v1/plans/{planId}/rebalance", active.path("planId").asText())
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan.days[0].exercises[0].restSeconds").value(180))
                .andExpect(jsonPath("$.data.plan.locks['" + path + "']").value("USER_LOCKED"));
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
                .andExpect(jsonPath("$.data.status").value("NO_CANDIDATE"));
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
        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"GENERAL_FITNESS",
                                 "weeklyFrequency":3,"sessionMinutes":60,"location":"GYM",
                                 "expectedVersion":0}
                                """))
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
