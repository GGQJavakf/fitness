package com.aifitness.assistant.privacy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
class PrivacyEndpointIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void exportAndDeletionRequireMatchingFreshWechatProof() throws Exception {
        String aliceAccess = login("privacy-alice");
        login("privacy-bob");

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "privacy-bob"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "privacy-alice"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scope[0]").value("PROFILE"))
                .andExpect(jsonPath("$.data.excludedRetentionCategories[0]").value("SECURITY_AUDIT"));

        mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"privacy-alice\",\"confirmationText\":\"NO\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void foreignAndUnknownDeletionRequestsHaveIdenticalSafeResponsesAndDuplicateIsIdempotent()
            throws Exception {
        String aliceAccess = login("deletion-alice");
        String bobAccess = login("deletion-bob");
        String requestJson = mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"deletion-alice\",\"confirmationText\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deletionScope[0]").value("PROFILE"))
                .andExpect(jsonPath("$.data.retainedCategories[0]").value("SECURITY_AUDIT"))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(requestJson).at("/data/id").asText();

        String duplicateJson = mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"deletion-alice\",\"confirmationText\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(duplicateJson).at("/data/id").asText()).isEqualTo(requestId);

        String foreign = mvc.perform(get("/api/v1/privacy/deletion-requests/" + requestId)
                        .header("Authorization", "Bearer " + bobAccess))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(get("/api/v1/privacy/deletion-requests/00000000-0000-4000-8000-000000000099")
                        .header("Authorization", "Bearer " + bobAccess))
                .andExpect(status().isNotFound())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(foreign).get("error"))
                .isEqualTo(objectMapper.readTree(unknown).get("error"));
    }

    private String login(String code) throws Exception {
        String json = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode data = objectMapper.readTree(json).get("data");
        return data.get("accessToken").asText();
    }
}
