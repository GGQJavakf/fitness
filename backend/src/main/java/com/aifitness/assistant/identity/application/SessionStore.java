package com.aifitness.assistant.identity.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Instant;

public interface SessionStore {

    WechatLoginService.SessionTokens issue(AuthenticatedUserId userId, Instant now);

    WechatLoginService.SessionTokens refresh(String refreshToken, Instant now);

    void revoke(String accessToken);

    AuthenticatedUserId authenticate(String accessToken, Instant now);
}
