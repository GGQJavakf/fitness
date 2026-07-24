package com.aifitness.assistant.privacy.domain;

import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record DeletionRequest(
        UUID id,
        UUID userId,
        Status status,
        Instant requestedAt,
        Instant updatedAt) {

    private static final Map<Status, Set<Status>> ALLOWED_TRANSITIONS = transitions();

    public DeletionRequest {
        Objects.requireNonNull(id, "id must not be null");
        Objects.requireNonNull(userId, "userId must not be null");
        Objects.requireNonNull(status, "status must not be null");
        Objects.requireNonNull(requestedAt, "requestedAt must not be null");
        Objects.requireNonNull(updatedAt, "updatedAt must not be null");
    }

    public static DeletionRequest requested(UUID userId, Instant now) {
        return new DeletionRequest(UUID.randomUUID(), userId, Status.REQUESTED, now, now);
    }

    public DeletionRequest transitionTo(Status next, Instant now) {
        if (!ALLOWED_TRANSITIONS.getOrDefault(status, Set.of()).contains(next)) {
            throw new IllegalStateException("invalid deletion request transition");
        }
        return new DeletionRequest(id, userId, next, requestedAt, now);
    }

    public boolean active() {
        return status != Status.COMPLETED && status != Status.REJECTED;
    }

    private static Map<Status, Set<Status>> transitions() {
        Map<Status, Set<Status>> result = new EnumMap<>(Status.class);
        result.put(Status.REQUESTED, Set.of(Status.ACCESS_REVOKED, Status.REJECTED));
        result.put(Status.ACCESS_REVOKED, Set.of(Status.BUSINESS_DATA_ANONYMIZED));
        result.put(Status.BUSINESS_DATA_ANONYMIZED, Set.of(Status.RETENTION_SEPARATED));
        result.put(Status.RETENTION_SEPARATED, Set.of(Status.COMPLETED));
        return Map.copyOf(result);
    }

    public enum Status {
        REQUESTED,
        ACCESS_REVOKED,
        BUSINESS_DATA_ANONYMIZED,
        RETENTION_SEPARATED,
        COMPLETED,
        REJECTED
    }
}
