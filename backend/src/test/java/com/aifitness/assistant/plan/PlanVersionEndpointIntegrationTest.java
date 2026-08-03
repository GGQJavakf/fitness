package com.aifitness.assistant.plan;

import static org.assertj.core.api.Assertions.assertThat;
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
class PlanVersionEndpointIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createsAndReadsImmutableVersionsWithExplicitWarningConfirmation() throws Exception {
        String token = loginAndConfigure();
        JsonNode candidate = generateCandidate(token);

        JsonNode created = json(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"" + candidate.path("candidateId").asText() + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.activeVersion.versionNumber").value(1))
                .andReturn().getResponse().getContentAsString());

        String planId = created.at("/data/planId").asText();
        JsonNode edited = candidate.path("plan").deepCopy();
        ((com.fasterxml.jackson.databind.node.ObjectNode) edited.at("/days/0/exercises/0"))
                .put("restSeconds", 120);
        String editBody = objectMapper.writeValueAsString(java.util.Map.of(
                "baseVersionNumber", 1,
                "plan", edited,
                "locks", java.util.Map.of()));

        JsonNode warning = json(mvc.perform(post("/api/v1/plans/{planId}/versions", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.data.warningConfirmationToken").isNotEmpty())
                .andReturn().getResponse().getContentAsString());

        JsonNode confirmedBody = objectMapper.readTree(editBody);
        ((com.fasterxml.jackson.databind.node.ObjectNode) confirmedBody)
                .put("warningConfirmationToken", warning.at("/data/warningConfirmationToken").asText());
        mvc.perform(post("/api/v1/plans/{planId}/versions", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(confirmedBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version.versionNumber").value(2));

        mvc.perform(get("/api/v1/plans/{planId}/versions/{versionNo}", planId, 1)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.versionNumber").value(1))
                .andExpect(jsonPath("$.data.plan.days[0].exercises[0].restSeconds")
                        .value(candidate.at("/plan/days/0/exercises/0/restSeconds").asInt()));

        String otherUserToken = login();
        mvc.perform(get("/api/v1/plans/{planId}/versions/{versionNo}", planId, 1)
                        .header("Authorization", "Bearer " + otherUserToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        mvc.perform(post("/api/v1/plans/{planId}/versions", planId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(editBody))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("VERSION_CONFLICT"))
                .andExpect(jsonPath("$.error.details.currentVersion").value(2));
    }

    @Test
    void replaysTheSameInitialPlanWhenTheCreateResponseMustBeRetried() throws Exception {
        String token = loginAndConfigure();
        String candidateId = generateCandidate(token).path("candidateId").asText();
        String requestBody = "{\"candidateId\":\"" + candidateId + "\"}";

        JsonNode first = json(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());
        JsonNode replay = json(mvc.perform(post("/api/v1/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString());

        assertThat(replay.at("/data/planId").asText()).isEqualTo(first.at("/data/planId").asText());
        assertThat(replay.at("/data/activeVersion/id").asText())
                .isEqualTo(first.at("/data/activeVersion/id").asText());
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
                        .content("{\"code\":\"plan-version-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(login).at("/data/accessToken").asText();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
