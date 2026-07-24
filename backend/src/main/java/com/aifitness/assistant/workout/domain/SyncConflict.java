package com.aifitness.assistant.workout.domain;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record SyncConflict(
        UUID id,
        UUID userId,
        String entityType,
        String entityKey,
        Map<String, String> localEvidence,
        Map<String, String> serverEvidence,
        Status status,
        Optional<Resolution> resolution,
        long version,
        Instant createdAt,
        Optional<Instant> resolvedAt) {
    public SyncConflict {
        Objects.requireNonNull(id, "conflict id must not be null");
        Objects.requireNonNull(userId, "conflict owner must not be null");
        if (entityType == null || entityType.isBlank() || entityKey == null || entityKey.isBlank()) {
            throw new IllegalArgumentException("conflict entity is required");
        }
        localEvidence = Map.copyOf(Objects.requireNonNull(localEvidence, "local evidence is required"));
        serverEvidence = Map.copyOf(Objects.requireNonNull(serverEvidence, "server evidence is required"));
        if (localEvidence.isEmpty() || serverEvidence.isEmpty() || version < 0) {
            throw new IllegalArgumentException("conflict evidence and version are required");
        }
        Objects.requireNonNull(status, "conflict status is required");
        resolution = Objects.requireNonNull(resolution, "conflict resolution is required");
        Objects.requireNonNull(createdAt, "conflict creation time is required");
        resolvedAt = Objects.requireNonNull(resolvedAt, "resolvedAt is required");
        if ((status == Status.OPEN && (resolution.isPresent() || resolvedAt.isPresent()))
                || (status == Status.RESOLVED && (resolution.isEmpty() || resolvedAt.isEmpty()))) {
            throw new IllegalArgumentException("conflict status and resolution must agree");
        }
    }

    public SyncConflict resolve(Resolution selected, long expectedVersion, Instant now) {
        Objects.requireNonNull(selected, "resolution is required");
        if (status != Status.OPEN || version != expectedVersion) {
            throw new IllegalStateException("sync conflict version does not match");
        }
        return new SyncConflict(id, userId, entityType, entityKey, localEvidence, serverEvidence,
                Status.RESOLVED, Optional.of(selected), version + 1, createdAt, Optional.of(now));
    }

    public enum Status { OPEN, RESOLVED }
    public enum Resolution { KEEP_LOCAL, KEEP_SERVER, KEEP_BOTH }
}
