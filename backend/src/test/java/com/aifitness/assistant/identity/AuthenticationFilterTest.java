package com.aifitness.assistant.identity;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.identity.api.AuthenticationFilter;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AuthenticationFilterTest {

    @Test
    void doesNotRewriteAuthenticatedDownstreamValidationFailureAsUnauthorized() {
        WechatLoginService loginService = mock(WechatLoginService.class);
        when(loginService.authenticate("valid-access-token"))
                .thenReturn(new AuthenticatedUserId(UUID.randomUUID()));
        AuthenticationFilter filter = new AuthenticationFilter(loginService, new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/profile");
        request.addHeader("Authorization", "Bearer valid-access-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThatThrownBy(() -> filter.doFilter(
                        request,
                        response,
                        (downstreamRequest, downstreamResponse) -> {
                            throw new IllegalArgumentException("business validation failure");
                        }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("business validation failure");
    }
}
