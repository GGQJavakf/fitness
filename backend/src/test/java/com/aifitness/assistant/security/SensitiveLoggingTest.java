package com.aifitness.assistant.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.FitnessAssistantApplication;
import com.aifitness.assistant.common.api.RequestIdFilter;
import com.aifitness.assistant.common.observability.SensitiveDataSanitizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@SpringBootTest(classes = FitnessAssistantApplication.class)
class SensitiveLoggingTest {

    @Autowired
    private RequestIdFilter requestIdFilter;

    @Test
    void registersTheFilterForTheSpringRequestChain() {
        assertThat(requestIdFilter).isNotNull();
    }

    @Test
    void onlyAllowsStructuredDiagnosticMetadata() {
        Map<String, Object> diagnosticData = new LinkedHashMap<>();
        diagnosticData.put("requestId", "request-01");
        diagnosticData.put("method", "POST");
        diagnosticData.put("route", "/api/v1/profile/{id}");
        diagnosticData.put("status", 400);
        diagnosticData.put("errorCode", "VALIDATION_FAILED");
        diagnosticData.put("duration", 17L);
        diagnosticData.put("event", "request_completed");
        diagnosticData.put("result", "rejected");
        diagnosticData.put("wechatCode", "wechat-temporary-code");
        diagnosticData.put("accessToken", "access-token-value");
        diagnosticData.put("Authorization", "Bearer secret");
        diagnosticData.put("Cookie", "session=secret");
        diagnosticData.put("injuryNote", "我的膝盖受伤了");
        diagnosticData.put("aiPrompt", "Describe the user health details");
        diagnosticData.put("exception", new IllegalStateException("backend secret"));
        diagnosticData.put(null, "unknown metadata");

        Map<String, Object> sanitized = SensitiveDataSanitizer.sanitize(diagnosticData);

        assertThat(sanitized).containsEntry("requestId", "request-01")
                .containsEntry("method", "POST")
                .containsEntry("route", "/api/v1/profile/{id}")
                .containsEntry("status", 400)
                .containsEntry("errorCode", "VALIDATION_FAILED")
                .containsEntry("duration", 17L)
                .containsEntry("event", "request_completed")
                .containsEntry("result", "rejected");
        assertThat(sanitized.keySet()).doesNotContain(
                "wechatCode", "accessToken", "Authorization", "Cookie", "injuryNote", "aiPrompt", "exception");
        assertThat(sanitized.values()).noneMatch(value -> String.valueOf(value).contains("secret"));
        assertThatThrownBy(() -> sanitized.put("event", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThat(diagnosticData).containsEntry("wechatCode", "wechat-temporary-code");
    }

    @Test
    void dropsFreeTextSmuggledIntoAllowedDiagnosticKeys() {
        Map<String, Object> diagnosticData = Map.of(
                "route", "/api/v1/auth?code=wechat-temporary-code",
                "errorCode", "third-party exception: secret",
                "event", "health note: knee injury",
                "result", "failure");

        Map<String, Object> sanitized = SensitiveDataSanitizer.sanitize(diagnosticData);

        assertThat(sanitized).containsOnly(Map.entry("result", "failure"));
    }

    @Test
    void retainsOnlyTheTrustedProfileRouteTemplate() {
        assertThat(SensitiveDataSanitizer.sanitize(Map.of("route", "/api/v1/profile/{id}")))
                .containsOnly(Map.entry("route", "/api/v1/profile/{id}"));
    }

    @Test
    void dropsLiteralIdentifiersAndNonTemplateRouteValues() {
        List<String> untrustedRoutes = List.of(
                "/api/v1/profile/123456",
                "13800138000",
                "550e8400-e29b-41d4-a716-446655440000",
                "39.9042,116.4074",
                "/tmp/user-health-note",
                "/" + "x".repeat(129));

        for (String route : untrustedRoutes) {
            assertThat(SensitiveDataSanitizer.sanitize(Map.of("route", route)))
                    .doesNotContainKey("route");
        }
    }

    @Test
    void redactsUnexpectedErrorsAndClearsMdcAfterAnExceptionalChain() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "request_01-accepted");
        MDC.put("requestId", "previous-request");

        try {
            filter.doFilter(request, response, (servletRequest, servletResponse) -> {
                assertThat(MDC.get("requestId")).isEqualTo("request_01-accepted");
                throw new IllegalStateException("do not log this exception message");
            });

            assertThat(response.getHeader("X-Request-Id")).isEqualTo("request_01-accepted");
            assertThat(response.getStatus()).isEqualTo(500);
            assertThat(response.getContentAsString())
                    .contains("INTERNAL_ERROR", "服务器内部错误", "request_01-accepted")
                    .doesNotContain("do not log this exception message");
            assertThat(MDC.get("requestId")).isEqualTo("previous-request");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void replacesInvalidRequestIdWithAnOpaqueUuidAndRemovesNewMdcEntry() throws Exception {
        RequestIdFilter filter = new RequestIdFilter();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/profile");
        MockHttpServletResponse response = new MockHttpServletResponse();
        request.addHeader("X-Request-Id", "invalid request id\nvalue");

        filter.doFilter(request, response, (servletRequest, servletResponse) ->
                assertThat(MDC.get("requestId")).matches("[A-Za-z0-9._-]{1,64}"));

        assertThat(response.getHeader("X-Request-Id")).isNotEqualTo("invalid request id\nvalue");
        assertThat(UUID.fromString(response.getHeader("X-Request-Id"))).isNotNull();
        assertThat(MDC.get("requestId")).isNull();
    }
}
