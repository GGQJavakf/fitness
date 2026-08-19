package com.aifitness.assistant.workout.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Append-only audit fact that removes a workout set from effective read models. */
public record WorkoutSetVoid(
        UUID id,
        UUID workoutSetId,
        UUID sessionId,
        UUID userId,
        String idempotencyKey,
        String payloadDigest,
        Reason reason,
        long appliedSessionVersion,
        Instant voidedAt) {

    public WorkoutSetVoid {
        Objects.requireNonNull(id, "void id must not be null");
        Objects.requireNonNull(workoutSetId, "workout set id must not be null");
        Objects.requireNonNull(sessionId, "session id must not be null");
        Objects.requireNonNull(userId, "user id must not be null");
        if (idempotencyKey == null || idempotencyKey.length() < 8 || idempotencyKey.length() > 128) {
            throw new IllegalArgumentException("void idempotency key length must be between 8 and 128");
        }
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("void payload digest must be a SHA-256 hex value");
        }
        Objects.requireNonNull(reason, "void reason must not be null");
        if (appliedSessionVersion < 1) {
            throw new IllegalArgumentException("applied session version must be positive");
        }
        Objects.requireNonNull(voidedAt, "voidedAt must not be null");
    }

    public enum Reason { USER_REQUESTED }
}
