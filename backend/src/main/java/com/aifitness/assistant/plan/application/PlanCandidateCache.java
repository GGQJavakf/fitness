package com.aifitness.assistant.plan.application;

import com.aifitness.assistant.identity.domain.AuthenticatedUserId;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Bounded, clock-driven cache for short-lived generated plan candidates. */
public final class PlanCandidateCache implements PlanCandidateStore {

    private final Map<String, PlanCandidateService.CandidateEnvelope> entries = new HashMap<>();
    private final Clock clock;
    private final int maximumSize;

    public PlanCandidateCache(Clock clock, int maximumSize) {
        this.clock = Objects.requireNonNull(clock, "clock must not be null");
        if (maximumSize < 1) {
            throw new IllegalArgumentException("maximumSize must be positive");
        }
        this.maximumSize = maximumSize;
    }

    @Override
    public synchronized void save(
            AuthenticatedUserId user,
            PlanCandidateService.CandidateEnvelope candidate) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(candidate, "candidate must not be null");
        removeExpired(clock.instant());
        entries.put(key(user, candidate.candidateId()), candidate);
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

    @Override
    public synchronized Optional<PlanCandidateService.CandidateEnvelope> find(
            AuthenticatedUserId user,
            String candidateId) {
        Objects.requireNonNull(user, "authenticated user must not be null");
        Objects.requireNonNull(candidateId, "candidateId must not be null");
        removeExpired(clock.instant());
        return Optional.ofNullable(entries.get(key(user, candidateId)));
    }

    synchronized int size() {
        removeExpired(clock.instant());
        return entries.size();
    }

    private void removeExpired(Instant now) {
        entries.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private static String key(
            AuthenticatedUserId user,
            String candidateId) {
        return user.value() + ":" + candidateId;
    }
}
