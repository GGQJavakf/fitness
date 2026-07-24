package com.aifitness.assistant.ai;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.aifitness.assistant.ai.api.AiController;
import com.aifitness.assistant.ai.api.AiExceptionHandler;
import com.aifitness.assistant.ai.application.AiContentService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

class AiEndpointIntegrationTest {
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        AiContentService service = mock(AiContentService.class);
        when(service.explainPlan(any(), eq("candidate-1"))).thenReturn(new AiContentService.GeneratedContent(
                AiContentService.Status.DEGRADED, "规则模板", "AI_DISABLED", Optional.empty()));
        mvc = MockMvcBuilders.standaloneSetup(new AiController(
                        service, Clock.fixed(Instant.parse("2026-07-24T12:00:00Z"), ZoneOffset.UTC)))
                .setControllerAdvice(new AiExceptionHandler())
                .setCustomArgumentResolvers(userResolver())
                .build();
    }

    @Test
    void returnsTypedTemplateDegradationWithoutExposingProviderDetails() throws Exception {
        mvc.perform(post("/api/v1/ai/plan-explanations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"candidate-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DEGRADED"))
                .andExpect(jsonPath("$.data.content").value("规则模板"))
                .andExpect(jsonPath("$.data.validationStatus").value("AI_DISABLED"))
                .andExpect(jsonPath("$.data.provider").doesNotExist());
    }

    @Test
    void rejectsAnEmptyReferenceWithTheUnifiedErrorEnvelope() throws Exception {
        mvc.perform(post("/api/v1/ai/plan-explanations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"candidateId\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_FAILED"));
    }

    private static HandlerMethodArgumentResolver userResolver() {
        return new HandlerMethodArgumentResolver() {
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.getParameterType() == AuthenticatedUserId.class;
            }

            public Object resolveArgument(
                    MethodParameter parameter,
                    ModelAndViewContainer container,
                    NativeWebRequest request,
                    org.springframework.web.bind.support.WebDataBinderFactory binderFactory) {
                return new AuthenticatedUserId(UUID.fromString("30000000-0000-0000-0000-000000000001"));
            }
        };
    }
}
