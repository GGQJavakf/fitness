package com.aifitness.assistant.mvp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.aifitness.assistant.workout.application.WorkoutCompletionOutboxProcessor;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MvpJourneyIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private WorkoutCompletionOutboxProcessor completionOutboxProcessor;

    @Test
    void completesTheRuleOwnedMvpJourneyWithAiSafelyDegraded() throws Exception {
        String token = login();
        configureProfileAndEquipment(token);

        JsonNode candidate = generateCandidate(token);
        String candidateId = candidate.path("candidateId").asText();
        assertThat(candidate.path("explanationStatus").asText()).isEqualTo("DEGRADED");
        mvc.perform(post("/api/v1/ai/plan-explanations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("candidateId", candidateId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.validationStatus").value("AI_DISABLED"));

        JsonNode plan = createPlan(token, candidateId);
        JsonNode session = startWorkout(token, plan);
        String sessionId = session.path("id").asText();
        int sessionVersion = startWorkSets(token, sessionId);
        int completedWorkSets = 0;

        for (JsonNode exercise : session.path("exercises")) {
            int workSets = exercise.at("/prescription/workSets").asInt();
            int reps = exercise.at("/prescription/repMax").asInt();
            for (int setOrder = 1; setOrder <= workSets; setOrder++) {
                sessionVersion = recordCompletedSet(
                        token, sessionId, exercise.path("id").asText(), setOrder, reps, sessionVersion);
                completedWorkSets++;
            }
        }

        String completionKey = "mvp-complete-" + UUID.randomUUID();
        mvc.perform(post("/api/v1/workout-sessions/{id}/complete", sessionId)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", completionKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":" + sessionVersion + ",\"completionType\":\"FULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.session.status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.complete").value(true))
                .andExpect(jsonPath("$.data.automaticProgressionEligible").value(true))
                .andExpect(jsonPath("$.data.completedWorkSets").value(completedWorkSets));

        // Completion commits before asynchronous progression delivery; drive one durable delivery deterministically.
        completionOutboxProcessor.processNext();

        mvc.perform(get("/api/v1/workout-sessions")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].sessionId").value(sessionId))
                .andExpect(jsonPath("$.data.items[0].trainingDayName").value("DAY_A"))
                .andExpect(jsonPath("$.data.items[0].status").value("COMPLETED"))
                .andExpect(jsonPath("$.data.items[0].completedWorkSets").value(completedWorkSets));
        mvc.perform(get("/api/v1/progression-recommendations")
                        .header("Authorization", bearer(token))
                        .queryParam("status", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andExpect(jsonPath("$.data[0].reasonCode").isNotEmpty())
                .andExpect(jsonPath("$.data[0].algorithmVersion").value("double-progression-v1"));
        mvc.perform(post("/api/v1/ai/workout-summaries")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("workoutSessionId", sessionId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.validationStatus").value("AI_DISABLED"))
                .andExpect(jsonPath("$.data.content").isNotEmpty());
    }

    private JsonNode generateCandidate(String token) throws Exception {
        String response = mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANDIDATE_READY"))
                .andReturn().getResponse().getContentAsString();
        return json(response).at("/data/candidate");
    }

    private JsonNode createPlan(String token, String candidateId) throws Exception {
        String response = mvc.perform(post("/api/v1/plans")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("candidateId", candidateId))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json(response).path("data");
    }

    private JsonNode startWorkout(String token, JsonNode plan) throws Exception {
        String clientKey = "mvp-session-" + UUID.randomUUID();
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("clientSessionKey", clientKey);
        request.put("planId", plan.path("planId").asText());
        request.put("planVersionNo", plan.at("/activeVersion/versionNumber").asInt());
        request.put("planDayId", plan.at("/activeVersion/plan/days/0/code").asText());
        String response = mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn().getResponse().getContentAsString();
        return json(response).path("data");
    }

    private int startWorkSets(String token, String sessionId) throws Exception {
        String response = mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json(response).at("/data/version").asInt();
    }

    private int recordCompletedSet(
            String token, String sessionId, String exerciseId, int setOrder, int reps, int expectedVersion)
            throws Exception {
        String setKey = "mvp-set-" + UUID.randomUUID();
        Map<String, Object> weight = Map.of("value", 1, "unit", "KG");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sessionExerciseId", exerciseId);
        body.put("clientOperationSeq", expectedVersion);
        body.put("setType", "WORK");
        body.put("setOrder", setOrder);
        body.put("target", Map.of("weight", weight, "reps", reps));
        body.put("actual", Map.of("weight", weight, "reps", reps));
        body.put("remainingReps", 2);
        body.put("completionStatus", "COMPLETED");
        body.put("completedAt", Instant.parse("2026-07-25T03:00:00Z").plusSeconds(expectedVersion).toString());
        body.put("expectedSessionVersion", expectedVersion);
        body.put("confirmAnomaly", false);
        String response = mvc.perform(put(
                        "/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", bearer(token))
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json(response).at("/data/sessionVersion").asInt();
    }

    private void configureProfileAndEquipment(String token) throws Exception {
        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"GENERAL_FITNESS",
                                 "weeklyFrequency":3,"sessionMinutes":60,"location":"GYM",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk());
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
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "],\"expectedVersion\":0}"))
                .andExpect(status().isOk());
    }

    private String login() throws Exception {
        String response = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"mvp-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json(response).at("/data/accessToken").asText();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
