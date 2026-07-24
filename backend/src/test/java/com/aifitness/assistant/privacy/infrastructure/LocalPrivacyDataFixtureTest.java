package com.aifitness.assistant.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.aifitness.assistant.identity.application.WechatLoginService;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.infrastructure.InMemorySessionStore;
import com.aifitness.assistant.privacy.application.PrivacyDeletionWorker;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LocalPrivacyDataFixtureTest {

    @Test
    void failedAccessRevocationIsRetriedBeforeTheCommandIsMarkedExecuted() {
        var sessions = new InMemorySessionStore();
        var user = new AuthenticatedUserId(UUID.randomUUID());
        Instant issuedAt = Instant.parse("2026-07-24T08:00:00Z");
        Instant retryAt = issuedAt.plusSeconds(60);
        var tokens = sessions.issue(user, issuedAt);
        var attempts = new AtomicInteger();
        var fixture = new LocalPrivacyDataFixture((subject, requestId) -> {
            if (attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("simulated revocation failure");
            }
            sessions.revokeAllSessionsAndBlockLogin(subject, requestId);
        });
        var command = new PrivacyDeletionWorker.LifecycleCommand(
                UUID.randomUUID(), user.value(), PrivacyDeletionWorker.LifecycleStep.REVOKE_ACCESS);

        assertThatThrownBy(() -> fixture.execute(command))
                .isInstanceOf(IllegalStateException.class);
        fixture.execute(command);

        assertThatThrownBy(() -> sessions.authenticate(tokens.accessToken(), retryAt))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);
        assertThatThrownBy(() -> sessions.refresh(tokens.refreshToken(), retryAt))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);
        assertThatThrownBy(() -> sessions.issue(user, retryAt))
                .isInstanceOf(WechatLoginService.AuthenticationRequiredException.class);
    }
}
