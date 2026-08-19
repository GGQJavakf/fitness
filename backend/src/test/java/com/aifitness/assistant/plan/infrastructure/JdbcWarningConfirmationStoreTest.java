package com.aifitness.assistant.plan.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

class JdbcWarningConfirmationStoreTest {
    @Test
    void storesOnlyDigestsAndAtomicallyConsumesTheUserBoundToken() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("INSERT INTO plan_warning_confirmation"), any(Object[].class)))
                .thenReturn(1);
        when(jdbc.update(contains("SET consumed_at"), any(Object[].class)))
                .thenReturn(1, 0);
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T09:00:00Z"), ZoneOffset.UTC);
        var store = new JdbcWarningConfirmationStore(
                jdbc, clock, new SecureRandom(new byte[] {1, 2, 3, 4}));
        AuthenticatedUserId user = new AuthenticatedUserId(UUID.randomUUID());

        String token = store.issue(user, "warning-fingerprint", clock.instant().plusSeconds(600));

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc).update(contains("INSERT INTO plan_warning_confirmation"), arguments.capture());
        assertThat(arguments.getValue()).noneMatch(token::equals);
        assertThat(arguments.getValue()).noneMatch("warning-fingerprint"::equals);
        assertThat(Arrays.stream(arguments.getValue())
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast)
                .filter(value -> value.length == 32))
                .hasSize(2);
        assertThat(store.consume(user, token, "warning-fingerprint", clock.instant())).isTrue();
        assertThat(store.consume(user, token, "warning-fingerprint", clock.instant())).isFalse();
    }
}
