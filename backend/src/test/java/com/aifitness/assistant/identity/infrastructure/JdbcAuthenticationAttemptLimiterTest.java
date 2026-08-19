package com.aifitness.assistant.identity.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.identity.application.AuthenticationAttemptLimiter;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcOperations;

class JdbcAuthenticationAttemptLimiterTest {
    @Test
    void claimsGlobalAndCredentialBucketsWithoutPersistingTheCredential() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("UPDATE authentication_rate_limit_bucket"), any(Object[].class)))
                .thenReturn(1, 1);
        var limiter = new JdbcAuthenticationAttemptLimiter(jdbc, 10, 600, Duration.ofMinutes(1));
        String credential = "secret-refresh-token";

        assertThat(limiter.allow(
                AuthenticationAttemptLimiter.Action.REFRESH_TOKEN,
                credential,
                Instant.parse("2026-08-11T09:00:30Z"))).isTrue();

        ArgumentCaptor<Object[]> arguments = ArgumentCaptor.forClass(Object[].class);
        verify(jdbc, times(2)).update(
                contains("UPDATE authentication_rate_limit_bucket"), arguments.capture());
        assertThat(arguments.getAllValues().stream().flatMap(Arrays::stream))
                .noneMatch(credential::equals);
        assertThat(arguments.getAllValues().stream().flatMap(Arrays::stream)
                .filter(byte[].class::isInstance)
                .map(byte[].class::cast))
                .allMatch(value -> value.length == 32);
    }
}
