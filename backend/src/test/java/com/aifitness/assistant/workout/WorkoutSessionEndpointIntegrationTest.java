package com.aifitness.assistant.workout;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class WorkoutSessionEndpointIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void startsOwnedImmutableSnapshotIdempotentlyAndEnforcesStatusVersion() throws Exception {
        String token = loginAndConfigure();
        JsonNode candidate = candidate(token);
        JsonNode plan = createPlan(token, candidate.at("/candidateId").asText());
        String planId = plan.at("/data/planId").asText();
        String dayCode = plan.at("/data/activeVersion/plan/days/0/code").asText();
        String clientKey = "workout-session-" + UUID.randomUUID();
        String body = objectMapper.writeValueAsString(java.util.Map.of(
                "clientSessionKey", clientKey,
                "planId", planId,
                "planVersionNo", 1,
                "planDayId", dayCode));

        JsonNode created = json(mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.exercises[0].exerciseName").isNotEmpty())
                .andExpect(jsonPath("$.data.exercises[0].prescription.unit").value("KG"))
                .andReturn().getResponse().getContentAsString());
        String sessionId = created.at("/data/id").asText();
        String sessionExerciseId = created.at("/data/exercises/0/id").asText();

        mvc.perform(post("/api/v1/workout-sessions")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", clientKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.id").value(sessionId));

        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"IN_PROGRESS\",\"expectedVersion\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_PROGRESS"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(put("/api/v1/workout-sessions/{id}/status", sessionId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"PAUSED\",\"expectedVersion\":0}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(1));

        String setKey = "workout-set-" + UUID.randomUUID();
        String setBody = """
                {"sessionExerciseId":"%s","clientOperationSeq":1,"setType":"WORK","setOrder":1,
                 "target":{"weight":{"value":20,"unit":"KG"},"reps":10},
                 "actual":{"weight":{"value":20,"unit":"KG"},"reps":9},
                 "remainingReps":2,"completionStatus":"COMPLETED",
                 "completedAt":"2026-07-24T08:00:00Z","expectedSessionVersion":1}
                """.formatted(sessionExerciseId);
        JsonNode savedSet = json(mvc.perform(put(
                        "/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.sessionVersion").value(2))
                .andExpect(jsonPath("$.data.actual.weight.unit").value("KG"))
                .andReturn().getResponse().getContentAsString());
        String setId = savedSet.at("/data/setId").asText();

        mvc.perform(put("/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.setId").value(setId))
                .andExpect(jsonPath("$.data.sessionVersion").value(2));

        mvc.perform(put("/api/v1/workout-sessions/{id}/sets/{clientSetKey}", sessionId, setKey)
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", setKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(setBody.replace("\"reps\":9", "\"reps\":8")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));

        String otherToken = login();
        mvc.perform(get("/api/v1/workout-sessions/{id}", sessionId)
                        .header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private JsonNode candidate(String token) throws Exception {
        return json(mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/candidate");
    }

    private JsonNode createPlan(String token, String candidateId) throws Exception {
        return json(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidateId + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
    }

    private String loginAndConfigure() throws Exception {
        String token = login();
        mvc.perform(put("/api/v1/profile")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"experience":"BEGINNER","goal":"GENERAL_FITNESS",
                                 "weeklyFrequency":3,"sessionMinutes":60,"location":"GYM",
                                 "expectedVersion":0}
                                """))
                .andExpect(status().isOk());
        String items = java.util.Arrays.stream(new String[] {"DUMBBELL", "BENCH", "CABLE", "MACHINE"})
                .map(type -> """
                        {"clientEquipmentKey":"%s","equipmentType":"%s",
                         "minIncrement":{"value":1,"unit":"KG"},
                         "availableLevels":[{"value":1,"unit":"KG"}]}
                        """.formatted(UUID.randomUUID(), type))
                .collect(java.util.stream.Collectors.joining(","));
        mvc.perform(put("/api/v1/profile/equipment")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[" + items + "],\"expectedVersion\":0}"))
                .andExpect(status().isOk());
        return token;
    }

    private String login() throws Exception {
        String login = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"workout-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(login).at("/data/accessToken").asText();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
