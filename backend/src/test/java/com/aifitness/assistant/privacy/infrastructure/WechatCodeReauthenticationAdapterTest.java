package com.aifitness.assistant.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WechatCodeReauthenticationAdapterTest {

    @Test
    void proofMustResolveAnExistingMatchingUserAndCanBeConsumedOnlyOnce() {
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        AuthenticatedUserId otherUser = new AuthenticatedUserId(UUID.randomUUID());
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        WechatCodeReauthenticationAdapter adapter = new WechatCodeReauthenticationAdapter(
                proof -> switch (proof) {
                    case "known" -> Optional.of(
                            new WechatIdentityResolver.ResolvedIdentity(user, clock.instant()));
                    case "other" -> Optional.of(
                            new WechatIdentityResolver.ResolvedIdentity(otherUser, clock.instant()));
                    default -> Optional.empty();
                },
                clock,
                Duration.ofMinutes(5));

        assertThat(adapter.verify(user, "unknown")).isFalse();
        assertThat(adapter.verify(user, "other")).isFalse();
        assertThat(adapter.verify(user, "known")).isTrue();
        assertThat(adapter.verify(user, "known")).isFalse();
    }

    @Test
    void expiredProofIsRejectedWithoutResolvingOrCreatingAnIdentity() {
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        WechatCodeReauthenticationAdapter adapter = new WechatCodeReauthenticationAdapter(
                proof -> Optional.of(new WechatIdentityResolver.ResolvedIdentity(
                        user, clock.instant().minusSeconds(2))),
                clock,
                Duration.ofSeconds(1));

        assertThat(adapter.verify(user, "short-lived")).isFalse();
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
