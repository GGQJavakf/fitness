package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.application.AuthenticationAttemptLimiter;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class InMemoryAuthenticationAttemptLimiterTest {
    @Test
    void appliesPerCredentialAndGlobalLimitsInEachWindow() {
        var limiter = new InMemoryAuthenticationAttemptLimiter(2, 3, Duration.ofMinutes(1));
        Instant now = Instant.parse("2026-08-11T09:00:30Z");

        assertThat(limiter.allow(
                AuthenticationAttemptLimiter.Action.WECHAT_CODE_LOGIN, "credential-a", now)).isTrue();
        assertThat(limiter.allow(
                AuthenticationAttemptLimiter.Action.WECHAT_CODE_LOGIN, "credential-a", now)).isTrue();
        assertThat(limiter.allow(
                AuthenticationAttemptLimiter.Action.WECHAT_CODE_LOGIN, "credential-a", now)).isFalse();
        assertThat(limiter.allow(
                AuthenticationAttemptLimiter.Action.WECHAT_CODE_LOGIN, "credential-b", now)).isFalse();
        assertThat(limiter.allow(
                AuthenticationAttemptLimiter.Action.WECHAT_CODE_LOGIN,
                "credential-a",
                now.plusSeconds(60))).isTrue();
    }
}
