package com.aifitness.assistant.plan;

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
class PlanExerciseOptionEndpointIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void returnsOnlyOwnedEligibleTemplatePrescriptionsForStructuralEditing() throws Exception {
        String token = loginAndConfigure();
        JsonNode candidate = generateCandidate(token);
        JsonNode active = json(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidate.path("candidateId").asText() + "\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        String planId = active.at("/data/planId").asText();
        String dayCode = active.at("/data/activeVersion/plan/days/0/code").asText();

        mvc.perform(get("/api/v1/plans/{planId}/exercise-options", planId)
                        .queryParam("dayCode", dayCode)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].exerciseCode").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].workSets").isNumber())
                .andExpect(jsonPath("$.data.items[0].repMin").isNumber())
                .andExpect(jsonPath("$.data.items[0].repMax").isNumber())
                .andExpect(jsonPath("$.data.items[0].restSeconds").isNumber())
                .andExpect(jsonPath("$.data.items[0].weightStatus").value("NEEDS_CALIBRATION"));

        String otherUser = login();
        mvc.perform(get("/api/v1/plans/{planId}/exercise-options", planId)
                        .queryParam("dayCode", dayCode)
                        .header("Authorization", "Bearer " + otherUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(get("/api/v1/plans/{planId}/exercise-options", planId)
                        .queryParam("dayCode", "DAY_A\nINJECTED")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));

        mvc.perform(get("/api/v1/plans/{planId}/day-options", planId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items[0].code").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].name").isNotEmpty())
                .andExpect(jsonPath("$.data.items[0].exercises[0].workSets").isNumber());

        mvc.perform(get("/api/v1/plans/{planId}/day-options", planId)
                        .header("Authorization", "Bearer " + otherUser))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));
    }

    private JsonNode generateCandidate(String token) throws Exception {
        return json(mvc.perform(post("/api/v1/plans/candidates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"profileVersion\":1,\"lockedFields\":{}}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString()).at("/data/candidate");
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
                        .content("{\"code\":\"plan-option-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(login).at("/data/accessToken").asText();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
