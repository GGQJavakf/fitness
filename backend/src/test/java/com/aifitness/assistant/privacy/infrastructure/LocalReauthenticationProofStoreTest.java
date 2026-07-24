package com.aifitness.assistant.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class LocalReauthenticationProofStoreTest {

    @Test
    void serverIssuedProofIsUserBoundSingleUseAndForgeryIsRejected() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        var store = new LocalReauthenticationProofStore(
                clock,
                Duration.ofMinutes(5),
                new SecureRandom(new byte[] {1, 2, 3}),
                matchingResolver(clock));
        var alice = new AuthenticatedUserId(UUID.randomUUID());
        var bob = new AuthenticatedUserId(UUID.randomUUID());
        var issued = store.issue(alice, alice.value().toString());

        assertThat(store.verify(alice, "alice|forged-suffix")).isFalse();
        assertThat(store.verify(bob, issued.proof())).isFalse();
        assertThat(store.verify(alice, issued.proof())).isTrue();
        assertThat(store.verify(alice, issued.proof())).isFalse();
        assertThat(retainedRecordCount(store)).isZero();
    }

    @Test
    void expiredServerIssuedProofCannotBeConsumed() {
        MutableClock clock = new MutableClock(Instant.parse("2026-07-24T08:00:00Z"));
        var store = new LocalReauthenticationProofStore(
                clock,
                Duration.ofMinutes(5),
                new SecureRandom(new byte[] {4, 5, 6}),
                matchingResolver(clock));
        var user = new AuthenticatedUserId(UUID.randomUUID());
        var issued = store.issue(user, user.value().toString());

        clock.advance(Duration.ofMinutes(5));

        assertThat(store.verify(user, issued.proof())).isFalse();
        assertThat(retainedRecordCount(store)).isZero();
    }

    @SuppressWarnings("unchecked")
    private static int retainedRecordCount(LocalReauthenticationProofStore store) {
        try {
            var records = LocalReauthenticationProofStore.class.getDeclaredField("records");
            records.setAccessible(true);
            return ((java.util.Map<String, ?>) records.get(store)).size();
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError(exception);
        }
    }

    private static WechatIdentityResolver matchingResolver(Clock clock) {
        return credential -> {
            try {
                return java.util.Optional.of(new WechatIdentityResolver.ResolvedIdentity(
                        new AuthenticatedUserId(UUID.fromString(credential)), clock.instant()));
            } catch (RuntimeException invalid) {
                return java.util.Optional.empty();
            }
        };
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) { this.now = now; }
        void advance(Duration duration) { now = now.plus(duration); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
