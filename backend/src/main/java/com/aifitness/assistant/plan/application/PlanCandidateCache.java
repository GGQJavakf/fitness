package com.aifitness.assistant.plan.application;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded, clock-driven cache for short-lived generated plan candidates. */
final class PlanCandidateCache {

    private final Map<String, PlanCandidateService.CandidateEnvelope> entries = new HashMap<>();
    private final Clock clock;
    private final int maximumSize;

    PlanCandidateCache(Clock clock, int maximumSize) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        this.maximumSize = maximumSize;
    }

    synchronized void put(String key, PlanCandidateService.CandidateEnvelope candidate) {
        Objects.requireNonNull(key, "key must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        removeExpired(clock.instant());
        entries.put(key, candidate);
        while (entries.size() > maximumSize) {
            String oldestKey = entries.entrySet().stream()
                    .min(Comparator
                            .comparing((Map.Entry<String, PlanCandidateService.CandidateEnvelope> entry) ->
                                    entry.getValue().expiresAt())
                            .thenComparing(Map.Entry::getKey))
                    .orElseThrow()
                    .getKey();
            entries.remove(oldestKey);
        }
    }

    synchronized Optional<PlanCandidateService.CandidateEnvelope> get(String key) {
        Objects.requireNonNull(key, "key must not be null");
        removeExpired(clock.instant());
        return Optional.ofNullable(entries.get(key));
    }

    synchronized int size() {
        removeExpired(clock.instant());
        return entries.size();
    }

    private void removeExpired(Instant now) {
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }
}
