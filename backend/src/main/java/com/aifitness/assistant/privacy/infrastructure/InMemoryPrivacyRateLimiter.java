package com.aifitness.assistant.privacy.infrastructure;

import com.aifitness.assistant.privacy.application.PrivacyRateLimitPort;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class InMemoryPrivacyRateLimiter implements PrivacyRateLimitPort {

    private final int maximumAttempts;
    private final Duration window;
    private final Map<Key, ArrayDeque<Instant>> attempts = new HashMap<>();

    InMemoryPrivacyRateLimiter(int maximumAttempts, Duration window) {
        if (maximumAttempts < 1 || window.isNegative() || window.isZero()) {
            throw new IllegalArgumentException("invalid privacy rate limit");
        }
        this.maximumAttempts = maximumAttempts;
        this.window = Objects.requireNonNull(window);
    }

    @Override
    public synchronized boolean allow(UUID userId, Action action, Instant now) {
        ArrayDeque<Instant> userAttempts = attempts.computeIfAbsent(
                new Key(userId, action), ignored -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!userAttempts.isEmpty() && userAttempts.peekFirst().isBefore(cutoff)) {
            userAttempts.removeFirst();
        }
        if (userAttempts.size() >= maximumAttempts) {
            return false;
        }
        userAttempts.addLast(now);
        return true;
    }

    private record Key(UUID userId, Action action) {}
}
