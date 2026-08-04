package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.identity.api.AuthController;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.infrastructure.InMemoryIdentityRepository;
import com.aifitness.assistant.identity.infrastructure.InMemorySessionStore;
import com.aifitness.assistant.identity.infrastructure.LocalWechatIdentityProvider;
import com.aifitness.assistant.identity.infrastructure.Sha256SubjectProtector;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AuthControllerContractTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-24T01:00:00Z"), ZoneOffset.UTC);
        WechatLoginService service = new WechatLoginService(
                new LocalWechatIdentityProvider(),
                new Sha256SubjectProtector(),
                new InMemoryIdentityRepository(),
                new InMemorySessionStore(),
                clock);
        mvc = MockMvcBuilders.standaloneSetup(new AuthController(service, clock, "test-app-id")).build();
    }

    @Test
    void loginResponseContainsOnlyChannelIndependentSessionData() throws Exception {
        String oneTimeCode = "temporary-code-must-not-escape";

        MvcResult result = mvc.perform(post("/api/v1/auth/wechat/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + oneTimeCode + "\"}"))
                .andExpect(status().isOk())
                .andReturn();

        String json = result.getResponse().getContentAsString();
        JsonNode root = objectMapper.readTree(json);
        assertThat(fieldNames(root)).containsExactlyInAnyOrder("data", "meta");
        assertThat(fieldNames(root.get("data")))
                .containsExactlyInAnyOrder("accessToken", "expiresAt", "refreshToken");
        assertThat(fieldNames(root.get("meta")))
                .containsExactlyInAnyOrder("requestId", "serverTime");
        assertThat(json)
                .doesNotContain(oneTimeCode)
                .doesNotContain("subject", "providerToken", "userId");
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }
}
