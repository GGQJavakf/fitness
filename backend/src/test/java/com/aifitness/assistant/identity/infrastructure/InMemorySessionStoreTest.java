package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemorySessionStoreTest {

    @Test
    void revokeAllAlsoRemovesRefreshSessionAfterAccessTokenExpired() {
        var store = new InMemorySessionStore();
        var user = new AuthenticatedUserId(UUID.randomUUID());
        Instant issuedAt = Instant.parse("2026-07-24T08:00:00Z");
        var tokens = store.issue(user, issuedAt);
        Instant afterAccessExpiry = issuedAt.plus(16, ChronoUnit.MINUTES);
        assertThatThrownBy(() -> store.authenticate(tokens.accessToken(), afterAccessExpiry))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);

        UUID requestId = UUID.randomUUID();
        store.revokeAllSessionsAndBlockLogin(user, requestId);
        store.revokeAllSessionsAndBlockLogin(user, requestId);

        assertThatThrownBy(() -> store.refresh(tokens.refreshToken(), afterAccessExpiry))
                .isInstanceOf(WechatLoginService.AccessRevokedException.class);
        assertThatThrownBy(() -> store.authenticate(tokens.accessToken(), afterAccessExpiry))
                .isInstanceOf(WechatLoginService.AccessRevokedException.class);
        assertThatThrownBy(() -> store.issue(user, afterAccessExpiry))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);
    }
}
