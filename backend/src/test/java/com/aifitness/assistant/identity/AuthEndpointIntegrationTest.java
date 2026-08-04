package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.aifitness.assistant.identity.application.ResourceOwnershipGuard;
import com.aifitness.assistant.identity.application.WechatIdentityProvider;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@SpringBootTest(
        classes = FitnessAssistantApplication.class,
        properties = "fitness.auth.wechat.app-id=test-app-id")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(AuthEndpointIntegrationTest.IdentityTestConfiguration.class)
class AuthEndpointIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private MutableClock identityTestClock;

    @Test
    void testProfileExposesLoginRefreshAndLogoutWithoutCredentialLeakage() throws Exception {
        JsonNode loginData = login("first-temporary-code");
        String accessToken = loginData.get("accessToken").asText();
        String refreshToken = loginData.get("refreshToken").asText();

        String refreshJson = mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode refreshedData = objectMapper.readTree(refreshJson).get("data");
        String refreshedAccess = refreshedData.get("accessToken").asText();
        String refreshedRefresh = refreshedData.get("refreshToken").asText();

        assertThat(refreshedRefresh).isNotEqualTo(refreshToken);
        assertThat(refreshJson).doesNotContain("first-temporary-code", "subject", "providerToken");

        mvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + refreshedAccess))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshedRefresh + "\"}"))
                .andExpect(status().isUnauthorized());

        assertThat(accessToken).isNotBlank();
    }

    @Test
    void invalidCredentialsUseStableSafeErrorResponses() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.error.message").value("请求参数不合法"));

        mvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"unknown-refresh-token\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void cloudBaseMiniProgramIdentityTakesPrecedenceOverTheFallbackWechatCode() throws Exception {
        String loginJson = mvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-WX-OPENID", "cloudbase-openid")
                        .header("X-WX-APPID", "test-app-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fallback-code\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertThat(loginJson)
                .doesNotContain("cloudbase-openid")
                .doesNotContain("fallback-code")
                .doesNotContain("test-app-id");
    }

    @Test
    void cloudBaseIdentityHeadersFailClosedWhenIncompleteOrForAnotherApp() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-WX-OPENID", "cloudbase-openid")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fallback-code\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));

        mvc.perform(post("/api/v1/auth/wechat/login")
                        .header("X-WX-OPENID", "cloudbase-openid")
                        .header("X-WX-APPID", "another-app-id")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"fallback-code\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void businessApiIsFailClosedForMissingOrForgedBearerTokens() throws Exception {
        String missingTokenJson = mvc.perform(get("/api/v1/profile")
                        .header("X-Request-Id", "missing-token-request"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"))
                .andReturn().getResponse().getContentAsString();
        assertThat(objectMapper.readTree(missingTokenJson).at("/meta/requestId").asText())
                .isEqualTo("missing-token-request");

        mvc.perform(get("/api/v1/profile")
                        .header("Authorization", "Bearer forged-access-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void malformedOrUnknownLoginInputUsesTheUniformErrorEnvelope() throws Exception {
        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.meta.requestId").isNotEmpty());

        mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"valid\",\"unexpected\":true}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    @Test
    void expiredAccessTokenIsRejectedByTheActualApiFilter() throws Exception {
        String accessToken = login("expiring-user").get("accessToken").asText();
        identityTestClock.advance(Duration.ofMinutes(16));

        mvc.perform(get("/api/v1/test-owned-resources/" + UUID.randomUUID())
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("AUTHENTICATION_REQUIRED"));
    }

    @Test
    void authenticatedUserResolverAndOwnedQueryReturn404ForCrossUserResourceIds() throws Exception {
        String aliceAccess = login("alice").get("accessToken").asText();
        String bobAccess = login("bob").get("accessToken").asText();

        String createJson = mvc.perform(post("/api/v1/test-owned-resources")
                        .header("Authorization", "Bearer " + aliceAccess))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String resourceId = objectMapper.readTree(createJson).get("resourceId").asText();

        mvc.perform(get("/api/v1/test-owned-resources/" + resourceId)
                        .header("Authorization", "Bearer " + aliceAccess))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/test-owned-resources/" + resourceId)
                        .header("Authorization", "Bearer " + bobAccess))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("RESOURCE_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("资源不存在"));
    }

    @Test
    void authenticatedBusinessValidationErrorsAreNotRewrittenAsAuthenticationFailures() throws Exception {
        String accessToken = login("validation-user").get("accessToken").asText();

        mvc.perform(get("/api/v1/test-owned-resources/validation-error")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private JsonNode login(String code) throws Exception {
        String json = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(json).get("data");
    }

    @TestConfiguration
    static class IdentityTestConfiguration {

        @Bean
        @Primary
        MutableClock identityTestClock() {
            return new MutableClock(Instant.parse("2026-07-24T01:00:00Z"));
        }

        @Bean
        @Primary
        WechatIdentityProvider identityTestProvider() {
            return code -> new WechatIdentityProvider.ProviderSubject(code);
        }

        @Bean
        OwnedResourceTestController ownedResourceTestController() {
            return new OwnedResourceTestController();
        }
    }

    @RestController
    @RequestMapping("/api/v1/test-owned-resources")
    static class OwnedResourceTestController {
        private final Map<UUID, ResourceOwnershipGuard.OwnedResource<String>> resources =
                new ConcurrentHashMap<>();
        private final ResourceOwnershipGuard ownershipGuard = new ResourceOwnershipGuard();

        @PostMapping
        Map<String, String> create(AuthenticatedUserId authenticatedUser) {
            UUID id = UUID.randomUUID();
            resources.put(id, new ResourceOwnershipGuard.OwnedResource<>(authenticatedUser, "private"));
            return Map.of("resourceId", id.toString());
        }

        @GetMapping("/{resourceId}")
        Map<String, String> get(@PathVariable UUID resourceId, AuthenticatedUserId authenticatedUser) {
            String value = ownershipGuard.requireOwnedResource(resourceId, authenticatedUser, resources::get);
            return Map.of("value", value);
        }

        @GetMapping("/validation-error")
        Map<String, String> validationError(AuthenticatedUserId authenticatedUser) {
            throw new IllegalArgumentException("test business validation failure");
        }
    }

    static final class MutableClock extends Clock {
        private Instant current;

        private MutableClock(Instant current) {
            this.current = current;
        }

        void advance(Duration duration) {
            current = current.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            if (!ZoneOffset.UTC.equals(zone)) {
                throw new IllegalArgumentException("test clock is UTC only");
            }
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }
}
