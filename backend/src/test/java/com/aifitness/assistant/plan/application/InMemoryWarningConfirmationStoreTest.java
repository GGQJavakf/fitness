package com.aifitness.assistant.plan.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryWarningConfirmationStoreTest {
    @Test
    void confirmationIsUserBoundSingleUseAndExpires() {
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T09:00:00Z"), ZoneOffset.UTC);
        var store = new InMemoryWarningConfirmationStore(clock);
        var owner = new AuthenticatedUserId(UUID.randomUUID());
        var another = new AuthenticatedUserId(UUID.randomUUID());
        String token = store.issue(owner, "fingerprint", clock.instant().plusSeconds(60));

        assertThat(store.consume(another, token, "fingerprint", clock.instant())).isFalse();
        assertThat(store.consume(owner, token, "different", clock.instant())).isFalse();
        assertThat(store.consume(owner, token, "fingerprint", clock.instant())).isTrue();
        assertThat(store.consume(owner, token, "fingerprint", clock.instant())).isFalse();

        String expiring = store.issue(owner, "fingerprint", clock.instant().plusSeconds(60));
        assertThat(store.consume(owner, expiring, "fingerprint", clock.instant().plusSeconds(60))).isFalse();
    }
}
