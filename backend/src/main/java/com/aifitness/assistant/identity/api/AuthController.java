package com.aifitness.assistant.identity.api;

import com.aifitness.assistant.common.api.ApiResponse;
import com.aifitness.assistant.common.api.ResponseMeta;
import com.aifitness.assistant.identity.application.WechatLoginService;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Profile({"local", "test", "staging-experience"})
public final class AuthController {

    private final WechatLoginService loginService;
    private final Clock clock;
    private final String expectedWechatAppId;

    public AuthController(
            WechatLoginService loginService,
            Clock clock,
            @Value("${fitness.auth.wechat.app-id:}") String expectedWechatAppId) {
        this.loginService = loginService;
        this.clock = clock;
        this.expectedWechatAppId = expectedWechatAppId;
    }

    @PostMapping("/wechat/login")
    public ApiResponse<SessionData> login(
            @RequestBody WechatLoginRequest request,
            @RequestHeader(value = "X-WX-OPENID", required = false) String cloudBaseOpenId,
            @RequestHeader(value = "X-WX-APPID", required = false) String cloudBaseAppId) {
        String trustedSubject = trustedCloudBaseSubject(cloudBaseOpenId, cloudBaseAppId);
        WechatLoginService.SessionTokens tokens = trustedSubject == null
                ? loginService.login(request.code())
                : loginService.loginTrustedWechatSubject(trustedSubject);
        return sessionResponse(tokens);
    }

    @PostMapping("/refresh")
    public ApiResponse<SessionData> refresh(@RequestBody RefreshSessionRequest request) {
        return sessionResponse(loginService.refresh(request.refreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<EmptyData> logout(@RequestHeader("Authorization") String authorization) {
        loginService.logout(bearerToken(authorization));
        return new ApiResponse<>(new EmptyData(), responseMeta());
    }

    private ApiResponse<SessionData> sessionResponse(WechatLoginService.SessionTokens tokens) {
        SessionData data = new SessionData(tokens.accessToken(), tokens.expiresAt(), tokens.refreshToken());
        return new ApiResponse<>(data, responseMeta());
    }

    private ResponseMeta responseMeta() {
        String requestId = MDC.get("requestId");
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        return new ResponseMeta(requestId, clock.instant());
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

    private String trustedCloudBaseSubject(String openId, String appId) {
        if (openId == null && appId == null) {
            return null;
        }
        if (!validHeaderValue(openId, 256)
                || !validHeaderValue(appId, 128)
                || expectedWechatAppId == null
                || expectedWechatAppId.isBlank()
                || !expectedWechatAppId.equals(appId)) {
            throw new WechatLoginService.AuthenticationRequiredException();
        }
        return openId;
    }

    private static boolean validHeaderValue(String value, int maxLength) {
        return value != null
                && !value.isBlank()
                && value.length() <= maxLength
                && value.equals(value.strip())
                && value.chars().noneMatch(character -> character < 0x20 || character == 0x7f);
    }

    public record WechatLoginRequest(String code) {}

    public record RefreshSessionRequest(String refreshToken) {}

    public record SessionData(String accessToken, Instant expiresAt, String refreshToken) {}

    public record EmptyData() {}
}
