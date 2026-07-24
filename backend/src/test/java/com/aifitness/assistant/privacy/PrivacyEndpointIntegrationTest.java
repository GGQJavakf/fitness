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
                        .header("X-Reauthentication-Proof", "privacy-bob|wrong-user"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "privacy-alice|export-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.resources[0].category").value("PROFILE"))
                .andExpect(jsonPath("$.data.scope[0]").value("PROFILE"))
                .andExpect(jsonPath("$.data.excludedRetentionCategories[0]").value("SECURITY_AUDIT"));

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "privacy-alice|export-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"privacy-alice|delete-invalid\",\"confirmationText\":\"NO\"}"))
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
                        .content("{\"reauthenticationProof\":\"deletion-alice|delete-1\",\"confirmationText\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deletionScope[0]").value("PROFILE"))
                .andExpect(jsonPath("$.data.retainedCategories[0]").value("SECURITY_AUDIT"))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(requestJson).at("/data/id").asText();

        String duplicateJson = mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"deletion-alice|delete-2\",\"confirmationText\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(duplicateJson).at("/data/id").asText()).isEqualTo(requestId);

        mvc.perform(post("/api/v1/privacy/deletion-requests/" + requestId + "/process")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "deletion-alice|process-1")
                        .header("X-Local-Deletion-Approval", "LOCAL_TEST_APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "deletion-alice|after-process"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources[0].recordCount").value(0))
                .andExpect(jsonPath("$.data.resources[1].recordCount").value(0));
        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + bobAccess)
                        .header("X-Reauthentication-Proof", "deletion-bob|isolated"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources[0].recordCount").value(1));

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

    @Test
    void privacyActionsAreRateLimitedPerUserAndActionWithAStableErrorCode() throws Exception {
        String access = login("privacy-rate-user");
        for (int attempt = 0; attempt < 20; attempt++) {
            mvc.perform(get("/api/v1/privacy/export")
                            .header("Authorization", "Bearer " + access)
                            .header("X-Reauthentication-Proof", "privacy-rate-user|" + attempt))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Reauthentication-Proof", "privacy-rate-user|limited"))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error.code").value("RATE_LIMITED"));
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
