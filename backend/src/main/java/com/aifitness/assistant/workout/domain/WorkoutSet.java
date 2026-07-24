package com.aifitness.assistant.workout.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public record WorkoutSet(
        UUID id,
        UUID sessionId,
        UUID sessionExerciseId,
        String clientSetKey,
        long clientOperationSeq,
        SetType setType,
        int setOrder,
        Performance target,
        Performance actual,
        Integer remainingReps,
        CompletionStatus completionStatus,
        Optional<Instant> completedAt,
        long serverRevision,
        Optional<AnomalyStatus> anomalyStatus,
        String payloadDigest) {

    public WorkoutSet {
        Objects.requireNonNull(id, "set id must not be null");
        Objects.requireNonNull(sessionId, "session id must not be null");
        Objects.requireNonNull(sessionExerciseId, "session exercise id must not be null");
        if (clientSetKey == null || clientSetKey.length() < 8 || clientSetKey.length() > 128) {
            throw new IllegalArgumentException("client set key length must be between 8 and 128");
        }
        if (clientOperationSeq < 1 || setOrder < 1 || serverRevision < 0) {
            throw new IllegalArgumentException("set sequence and revisions must be valid");
        }
        Objects.requireNonNull(setType, "set type must not be null");
        Objects.requireNonNull(target, "target must not be null");
        Objects.requireNonNull(actual, "actual must not be null");
        if (target.reps() < 1 || remainingReps != null && remainingReps < 0) {
            throw new IllegalArgumentException("set repetitions must be valid");
        }
        Objects.requireNonNull(completionStatus, "completion status must not be null");
        completedAt = Objects.requireNonNull(completedAt, "completedAt must not be null");
        if ((completionStatus == CompletionStatus.COMPLETED) != completedAt.isPresent()) {
            throw new IllegalArgumentException("completed set and completion time must agree");
        }
        anomalyStatus = Objects.requireNonNull(anomalyStatus, "anomalyStatus must not be null");
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payload digest must be a SHA-256 hex value");
        }
    }

    public record Performance(BigDecimal weight, String unit, Integer reps) {
        public Performance {
            Objects.requireNonNull(weight, "weight must not be null");
            if (weight.signum() < 0) {
                throw new IllegalArgumentException("weight must not be negative");
            }
            if (!"KG".equals(unit)) {
                throw new IllegalArgumentException("P0 workout sets only support KG");
            }
            if (reps == null || reps < 0) {
                throw new IllegalArgumentException("repetitions must not be missing or negative");
            }
            weight = weight.stripTrailingZeros();
        }
    }

    public enum SetType { WARMUP, WORK, EXTRA }
    public enum CompletionStatus { PLANNED, COMPLETED, SKIPPED }
    public enum AnomalyStatus { CONFIRMED_EXCLUDED }
}
