package com.aifitness.assistant.privacy.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.aifitness.assistant.privacy.application.PrivacyRateLimitPort;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcOperations;

class JdbcPrivacyRateLimiterTest {

    private static final Instant NOW = Instant.parse("2026-08-11T08:00:30Z");

    @Test
    void atomicallyIncrementsAnExistingBucketOnlyBelowTheLimit() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("UPDATE privacy_rate_limit_bucket"),
                any(Object[].class))).thenReturn(1);
        var limiter = new JdbcPrivacyRateLimiter(jdbc, 20, Duration.ofMinutes(1));

        assertThat(limiter.allow(UUID.randomUUID(),
                PrivacyRateLimitPort.Action.EXPORT, NOW)).isTrue();
    }

    @Test
    void insertsTheFirstAttemptForANewSharedBucket() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("UPDATE privacy_rate_limit_bucket"),
                any(Object[].class))).thenReturn(0);
        when(jdbc.update(contains("INSERT INTO privacy_rate_limit_bucket"),
                any(Object[].class))).thenReturn(1);
        var limiter = new JdbcPrivacyRateLimiter(jdbc, 20, Duration.ofMinutes(1));

        assertThat(limiter.allow(UUID.randomUUID(),
                PrivacyRateLimitPort.Action.DELETE_REQUEST, NOW)).isTrue();
    }

    @Test
    void retriesTheBoundedIncrementWhenAnotherInstanceCreatesTheBucket() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("UPDATE privacy_rate_limit_bucket"),
                any(Object[].class))).thenReturn(0, 1);
        when(jdbc.update(contains("INSERT INTO privacy_rate_limit_bucket"),
                any(Object[].class))).thenThrow(new DuplicateKeyException("concurrent bucket"));
        var limiter = new JdbcPrivacyRateLimiter(jdbc, 20, Duration.ofMinutes(1));

        assertThat(limiter.allow(UUID.randomUUID(),
                PrivacyRateLimitPort.Action.REAUTHENTICATION_PROOF_ISSUE, NOW)).isTrue();
    }

    @Test
    void rejectsWhenTheSharedBucketIsAlreadyExhausted() {
        JdbcOperations jdbc = mock(JdbcOperations.class);
        when(jdbc.update(contains("UPDATE privacy_rate_limit_bucket"),
                any(Object[].class))).thenReturn(0);
        when(jdbc.update(contains("INSERT INTO privacy_rate_limit_bucket"),
                any(Object[].class))).thenThrow(new DuplicateKeyException("existing bucket"));
        var limiter = new JdbcPrivacyRateLimiter(jdbc, 20, Duration.ofMinutes(1));

        assertThat(limiter.allow(UUID.randomUUID(),
                PrivacyRateLimitPort.Action.EXPORT_READ, NOW)).isFalse();
    }
}
