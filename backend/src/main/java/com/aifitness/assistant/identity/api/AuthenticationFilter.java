package com.aifitness.assistant.identity.api;

import com.aifitness.assistant.common.api.ApiError;
import com.aifitness.assistant.common.api.ApiErrorResponse;
import com.aifitness.assistant.common.api.ErrorCode;
import com.aifitness.assistant.common.api.ErrorMeta;
import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Profile({"local", "test", "staging-experience"})
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public final class AuthenticationFilter extends OncePerRequestFilter {

    static final String AUTHENTICATED_USER_ATTRIBUTE = AuthenticatedUserId.class.getName();
    private static final Set<String> ANONYMOUS_POST_PATHS = Set.of(
            "/api/v1/auth/wechat/login", "/api/v1/auth/refresh");

    private final WechatLoginService loginService;
    private final ObjectMapper objectMapper;

    public AuthenticationFilter(WechatLoginService loginService, ObjectMapper objectMapper) {
        this.loginService = loginService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String requestUri = request.getRequestURI();
        String contextPath = request.getContextPath();
        String path = requestUri.substring(Math.min(contextPath.length(), requestUri.length()));
        if (!path.startsWith("/api/v1/")) {
            return true;
        }
        return "POST".equals(request.getMethod()) && ANONYMOUS_POST_PATHS.contains(path);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        AuthenticatedUserId userId;
        try {
            userId = loginService.authenticate(bearerToken(request.getHeader("Authorization")));
        } catch (IllegalArgumentException | WechatLoginService.AuthenticationRequiredException exception) {
            writeUnauthorized(response);
            return;
        }
        request.setAttribute(AUTHENTICATED_USER_ATTRIBUTE, userId);
        filterChain.doFilter(request, response);
    }

    private static String bearerToken(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        String token = authorization.substring("Bearer ".length());
        if (token.isBlank()) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        return token;
    }

    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        ApiError error = new ApiError(
                ErrorCode.AUTHENTICATION_REQUIRED,
                "登录状态已失效",
                List.of(),
                Map.of(),
                false);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getWriter(), new ApiErrorResponse(error, new ErrorMeta(requestId)));
    }
}
