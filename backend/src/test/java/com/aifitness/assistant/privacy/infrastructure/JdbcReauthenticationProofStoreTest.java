package com.aifitness.assistant.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.identity.application.WechatIdentityResolver;
import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

class JdbcReauthenticationProofStoreTest {

    @Test
    void storesOnlyDigestAndConsumesProofWithOneAtomicUpdate() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("INSERT INTO privacy_reauthentication_proof"),
                any(Object[].class))).thenReturn(1);
        when(jdbc.update(contains("SET consumed_at"), any(Object[].class)))
                .thenReturn(1, 0);
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), ZoneOffset.UTC);
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());
        WechatIdentityResolver identities = credential -> java.util.Optional.of(
                new WechatIdentityResolver.ResolvedIdentity(user, clock.instant()));
        var store = new JdbcReauthenticationProofStore(
                jdbc, clock, Duration.ofMinutes(5),
                new SecureRandom(new byte[] {1, 2, 3, 4}), identities);

        var issued = store.issue(user, "one-time-code");

        ArgumentCaptor<Object[]> insertArguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO privacy_reauthentication_proof"),
                insertArguments.capture());
        assertThat(insertArguments.getValue())
                .noneMatch(value -> issued.proof().equals(value));
        assertThat(Arrays.stream(insertArguments.getValue())
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast)
                .anyMatch(value -> value.length == 32)).isTrue();
        assertThat(store.verify(user, issued.proof())).isTrue();
        assertThat(store.verify(user, issued.proof())).isFalse();
    }

    @Test
    void rejectsCredentialThatDoesNotResolveToTheProofOwnerWithoutWriting() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T08:00:00Z"), ZoneOffset.UTC);
        AuthenticatedUserId owner = new AuthenticatedUserId(UUID.randomUUID());
        AuthenticatedUserId another = new AuthenticatedUserId(UUID.randomUUID());
        WechatIdentityResolver identities = ignored -> java.util.Optional.of(
                new WechatIdentityResolver.ResolvedIdentity(another, clock.instant()));
        var store = new JdbcReauthenticationProofStore(
                jdbc, clock, Duration.ofMinutes(5), new SecureRandom(), identities);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> store.issue(owner, "wrong-user-code"))
                .isInstanceOf(com.aifitness.assistant.privacy.application.PrivacyRequestService
                        .ReauthenticationRequiredException.class);

        org.mockito.Mockito.verify(jdbc, org.mockito.Mockito.never())
                .update(anyString(), any(Object[].class));
    }
}
