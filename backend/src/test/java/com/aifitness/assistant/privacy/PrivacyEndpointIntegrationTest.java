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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(classes = FitnessAssistantApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PrivacyEndpointIntegrationTest.PrivacyTestConfiguration.class)
class PrivacyEndpointIntegrationTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private MutableClock privacyTestClock;

    @Test
    void exportAndDeletionRequireMatchingFreshWechatProof() throws Exception {
        String aliceAccess = login("privacy-alice");
        String bobAccess = login("privacy-bob");

        String duplicateCode = "privacy-alice|duplicate-proof-code";
        issueProof(aliceAccess, duplicateCode);
        mvc.perform(post("/api/v1/privacy/reauthentication-proofs")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + duplicateCode + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", "privacy-alice|forged-suffix"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        mvc.perform(post("/api/v1/privacy/reauthentication-proofs")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"privacy-bob|wrong-user-proof\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        String aliceProof = issueFreshProof(aliceAccess, "privacy-alice");
        String exportJson = mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", aliceProof))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isNotEmpty())
                .andExpect(jsonPath("$.data.status").value("READY"))
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andExpect(jsonPath("$.data.resources[0].category").value("PROFILE"))
                .andExpect(jsonPath("$.data.resources[0].records[0].summary").value("成年用户训练档案"))
                .andExpect(jsonPath("$.data.scope[0]").value("PROFILE"))
                .andExpect(jsonPath("$.data.excludedRetentionCategories[0]").value("SECURITY_AUDIT"))
                .andReturn().getResponse().getContentAsString();
        String exportId = objectMapper.readTree(exportJson).at("/data/id").asText();

        mvc.perform(get("/api/v1/privacy/exports/" + exportId)
                        .header("Authorization", "Bearer " + aliceAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(exportId));
        mvc.perform(get("/api/v1/privacy/exports/" + exportId)
                        .header("Authorization", "Bearer " + bobAccess))
                .andExpect(status().isNotFound());

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", aliceProof))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("REAUTHENTICATION_REQUIRED"));

        String expiringProof = issueFreshProof(aliceAccess, "privacy-alice");
        privacyTestClock.advance(Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", expiringProof))
                .andExpect(status().isUnauthorized());
        privacyTestClock.advance(Duration.ofMinutes(5));
        mvc.perform(get("/api/v1/privacy/exports/" + exportId)
                        .header("Authorization", "Bearer " + aliceAccess))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"forged\",\"confirmationText\":\"NO\"}"))
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
                        .content("{\"reauthenticationProof\":\"" + issueFreshProof(aliceAccess, "deletion-alice")
                                + "\",\"confirmationText\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.deletionScope[0]").value("PROFILE"))
                .andExpect(jsonPath("$.data.retainedCategories[0]").value("SECURITY_AUDIT"))
                .andReturn().getResponse().getContentAsString();
        String requestId = objectMapper.readTree(requestJson).at("/data/id").asText();

        String duplicateJson = mvc.perform(post("/api/v1/privacy/deletion-requests")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reauthenticationProof\":\"" + issueFreshProof(aliceAccess, "deletion-alice")
                                + "\",\"confirmationText\":\"DELETE\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(duplicateJson).at("/data/id").asText()).isEqualTo(requestId);

        mvc.perform(post("/api/v1/privacy/deletion-requests/" + requestId + "/process")
                        .header("Authorization", "Bearer " + aliceAccess)
                        .header("X-Reauthentication-Proof", issueFreshProof(aliceAccess, "deletion-alice"))
                        .header("X-Local-Deletion-Approval", "LOCAL_TEST_APPROVED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        mvc.perform(get("/api/v1/profile")
                        .header("Authorization", "Bearer " + aliceAccess))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"deletion-alice|relogin\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + bobAccess)
                        .header("X-Reauthentication-Proof", issueFreshProof(bobAccess, "deletion-bob")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.resources[0].recordCount").value(1))
                .andExpect(jsonPath("$.data.resources[0].records[0].id").isNotEmpty());

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
                            .header("X-Reauthentication-Proof", issueFreshProof(access, "privacy-rate-user")))
                    .andExpect(status().isOk());
        }

        mvc.perform(get("/api/v1/privacy/export")
                        .header("Authorization", "Bearer " + access)
                        .header("X-Reauthentication-Proof", "rate-limit-checked-before-proof"))
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

    private String issueProof(String accessToken, String code) throws Exception {
        String json = mvc.perform(post("/api/v1/privacy/reauthentication-proofs")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.expiresAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).at("/data/proof").asText();
    }

    private String issueFreshProof(String accessToken, String subject) throws Exception {
        return issueProof(accessToken, subject + "|" + java.util.UUID.randomUUID());
    }

    @TestConfiguration
    static class PrivacyTestConfiguration {
        @Bean @Primary MutableClock privacyTestClock() {
            return new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        }
    }

    static final class MutableClock extends Clock {
        private Instant now;
        private MutableClock(Instant now) { this.now = now; }
        synchronized void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public synchronized Instant instant() { return now; }
    }
}
