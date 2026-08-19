package com.aifitness.assistant.progression.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Immutable, replayable facts selected for one progression decision. */
public record ProgressionInput(
        String schemaVersion,
        UUID userId,
        UUID exerciseId,
        String variantKey,
        String unit,
        Instant selectedAt,
        List<EffectiveSet> effectiveSets,
        List<ExcludedSet> excludedSets,
        List<FailedSetFact> failedSets,
        List<SafetyFlagFact> safetyFlags) {

    public ProgressionInput(
            String schemaVersion,
            UUID userId,
            UUID exerciseId,
            String variantKey,
            String unit,
            Instant selectedAt,
            List<EffectiveSet> effectiveSets,
            List<ExcludedSet> excludedSets) {
        this(schemaVersion, userId, exerciseId, variantKey, unit, selectedAt,
                effectiveSets, excludedSets, List.of(), List.of());
    }

    public ProgressionInput {
        schemaVersion = required(schemaVersion, "schema version");
        Objects.requireNonNull(userId, "user id must not be null");
        Objects.requireNonNull(exerciseId, "exercise id must not be null");
        variantKey = required(variantKey, "variant key");
        if (!"KG".equals(unit)) {
            throw new IllegalArgumentException("P0 progression input only supports KG");
        }
        Objects.requireNonNull(selectedAt, "selection time must not be null");
        effectiveSets = List.copyOf(Objects.requireNonNull(effectiveSets, "effective sets must not be null"));
        excludedSets = List.copyOf(Objects.requireNonNull(excludedSets, "excluded sets must not be null"));
        failedSets = List.copyOf(Objects.requireNonNull(failedSets, "failed sets must not be null"));
        safetyFlags = List.copyOf(Objects.requireNonNull(safetyFlags, "safety flags must not be null"));
    }

    public record EffectiveSet(
            UUID factId,
            UUID sessionId,
            int setOrder,
            Instant completedAt,
            BigDecimal weightKg,
            int reps,
            Optional<Integer> remainingReps,
            long serverRevision,
            String payloadDigest) {
        public EffectiveSet {
            Objects.requireNonNull(factId, "fact id must not be null");
            Objects.requireNonNull(sessionId, "session id must not be null");
            Objects.requireNonNull(completedAt, "completion time must not be null");
            Objects.requireNonNull(weightKg, "weight must not be null");
            if (setOrder < 1 || weightKg.signum() < 0 || reps < 0 || serverRevision < 0) {
                throw new IllegalArgumentException("effective set order and values must be valid");
            }
            weightKg = weightKg.stripTrailingZeros();
            remainingReps = Objects.requireNonNull(remainingReps, "remaining reps must not be null");
            if (remainingReps.filter(value -> value < 0).isPresent()) {
                throw new IllegalArgumentException("remaining reps must not be negative");
            }
            if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("payload digest must be a SHA-256 hex value");
            }
        }
    }

    public record ExcludedSet(UUID factId, UUID sessionId, List<ExclusionReason> reasons) {
        public ExcludedSet {
            Objects.requireNonNull(factId, "fact id must not be null");
            Objects.requireNonNull(sessionId, "session id must not be null");
            reasons = List.copyOf(Objects.requireNonNull(reasons, "exclusion reasons must not be null"));
            if (reasons.isEmpty()) throw new IllegalArgumentException("excluded set must have a reason");
        }
    }

    public record FailedSetFact(
            UUID factId,
            UUID sessionId,
            int setOrder,
            Instant completedAt,
            long serverRevision,
            String payloadDigest) {
        public FailedSetFact {
            validateAuditFact(factId, sessionId, setOrder, completedAt, serverRevision, payloadDigest);
        }
    }

    public record SafetyFlagFact(
            UUID factId,
            UUID sessionId,
            FactSetType setType,
            int setOrder,
            SafetyFlag safetyFlag,
            Instant completedAt,
            long serverRevision,
            String payloadDigest) {
        public SafetyFlagFact {
            Objects.requireNonNull(setType, "set type must not be null");
            Objects.requireNonNull(safetyFlag, "safety flag must not be null");
            validateAuditFact(factId, sessionId, setOrder, completedAt, serverRevision, payloadDigest);
        }
    }

    public enum FactSetType { WARMUP, WORK, EXTRA }
    public enum SafetyFlag { PAIN, INJURY, CHEST_DISCOMFORT, DIZZINESS, SEVERE_UNWELL }

    public enum ExclusionReason {
        USER_MISMATCH,
        EXERCISE_MISMATCH,
        WARMUP_SET,
        EXTRA_SET,
        INCOMPLETE_SESSION,
        INCOMPLETE_SET,
        VARIANT_CHANGED,
        UNIT_CHANGED,
        MISSING_WEIGHT,
        ANOMALOUS_INPUT,
        SUPERSEDED_REVISION
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static void validateAuditFact(
            UUID factId, UUID sessionId, int setOrder, Instant completedAt, long serverRevision,
            String payloadDigest) {
        Objects.requireNonNull(factId, "fact id must not be null");
        Objects.requireNonNull(sessionId, "session id must not be null");
        Objects.requireNonNull(completedAt, "completion time must not be null");
        if (setOrder < 1 || serverRevision < 0) {
            throw new IllegalArgumentException("audit fact sequence and revision must be valid");
        }
        if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("payload digest must be a SHA-256 hex value");
        }
    }
}
