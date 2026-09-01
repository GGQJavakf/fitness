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
import com.fasterxml.jackson.databind.node.ObjectNode;
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
class CandidateCommitEndpointIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void warningConfirmationCommitsEditedCandidateDirectlyAsVersionOneAndReplays() throws Exception {
        String token = loginAndConfigure();
        JsonNode candidate = generateCandidate(token);
        ObjectNode edited = candidate.path("plan").deepCopy();
        edited.put("name", "最终编辑计划");
        ((ObjectNode) edited.at("/days/0/exercises/0")).put("restSeconds", 120);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateId", candidate.path("candidateId").asText());
        body.put("expectedActiveVersionNumber", 0);
        body.put("plan", edited);
        body.put("locks", Map.of());
        String key = "candidate-commit-endpoint-0001";

        JsonNode warning = json(mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING_CONFIRMATION_REQUIRED"))
                .andExpect(jsonPath("$.data.version").doesNotExist())
                .andReturn().getResponse().getContentAsString());
        mvc.perform(get("/api/v1/plans/active").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());

        body.put("warningConfirmationToken", warning.at("/data/warningConfirmationToken").asText());
        JsonNode created = json(mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andExpect(jsonPath("$.data.version.versionNumber").value(1))
                .andExpect(jsonPath("$.data.version.plan.name").value("最终编辑计划"))
                .andReturn().getResponse().getContentAsString());
        JsonNode replay = json(mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.version.versionNumber").value(1))
                .andReturn().getResponse().getContentAsString());

        assertThat(replay.at("/data/version/id").asText()).isEqualTo(created.at("/data/version/id").asText());
        mvc.perform(get("/api/v1/plans/active").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeVersion.versionNumber").value(1));
    }

    @Test
    void rejectsForeignCandidateAndDifferentPayloadKeyReuseWithoutExtraVersions() throws Exception {
        String owner = loginAndConfigure();
        JsonNode candidate = generateCandidate(owner);
        String other = loginAndConfigure();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("candidateId", candidate.path("candidateId").asText());
        body.put("expectedActiveVersionNumber", 0);
        body.put("plan", candidate.path("plan"));
        body.put("locks", Map.of());
        Map<String, Object> missingExpectedVersion = new LinkedHashMap<>(body);
        missingExpectedVersion.remove("expectedActiveVersionNumber");
        mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", "candidate-commit-missing-version-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(missingExpectedVersion)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + other)
                        .header("Idempotency-Key", "candidate-commit-foreign-0001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"));

        String key = "candidate-commit-endpoint-0002";
        JsonNode warning = json(mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WARNING_CONFIRMATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString());
        body.put("warningConfirmationToken", warning.at("/data/warningConfirmationToken").asText());
        mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated());
        ObjectNode changed = candidate.path("plan").deepCopy();
        changed.put("name", "不同负载");
        Map<String, Object> different = new LinkedHashMap<>(body);
        different.put("plan", changed);
        mvc.perform(post("/api/v1/plans/candidate-commits")
                        .header("Authorization", "Bearer " + owner)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(different)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_REUSED"));
        mvc.perform(get("/api/v1/plans/active").header("Authorization", "Bearer " + owner))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.activeVersion.versionNumber").value(1));
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
                        .content("{\"code\":\"candidate-commit-" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json(login).at("/data/accessToken").asText();
    }

    private JsonNode json(String value) throws Exception {
        return objectMapper.readTree(value);
    }
}
