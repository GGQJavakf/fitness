package com.aifitness.assistant.progression.application;

import com.aifitness.assistant.progression.domain.ProgressionInput;
import com.aifitness.assistant.progression.domain.ProgressionInput.ExcludedSet;
import com.aifitness.assistant.progression.domain.ProgressionInput.ExclusionReason;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Applies explicit, deterministic eligibility rules before progression evaluation. */
public final class EffectiveSetSelector {
    private static final String SCHEMA_VERSION = "progression-input-v1";

    public ProgressionInput select(
            SelectionCriteria criteria, List<RawSetFact> rawFacts, Instant selectedAt) {
        Objects.requireNonNull(criteria, "selection criteria must not be null");
        List<RawSetFact> facts = List.copyOf(Objects.requireNonNull(rawFacts, "raw facts must not be null"));
        Objects.requireNonNull(selectedAt, "selection time must not be null");
        List<ProgressionInput.EffectiveSet> effective = new ArrayList<>();
        List<ExcludedSet> excluded = new ArrayList<>();
        for (RawSetFact fact : facts) {
            List<ExclusionReason> reasons = reasons(criteria, fact);
            if (!reasons.isEmpty()) {
                excluded.add(new ExcludedSet(fact.factId(), fact.sessionId(), reasons));
                continue;
            }
            effective.add(new ProgressionInput.EffectiveSet(
                    fact.factId(), fact.sessionId(), fact.setOrder(), fact.completedAt(), fact.weight(), fact.reps(),
                    fact.remainingReps(), fact.serverRevision(), fact.payloadDigest()));
        }
        effective.sort(Comparator.comparing(ProgressionInput.EffectiveSet::completedAt)
                .thenComparing(ProgressionInput.EffectiveSet::sessionId)
                .thenComparingInt(ProgressionInput.EffectiveSet::setOrder)
                .thenComparing(ProgressionInput.EffectiveSet::factId));
        excluded.sort(Comparator.comparing(ExcludedSet::sessionId).thenComparing(ExcludedSet::factId));
        return new ProgressionInput(
                SCHEMA_VERSION, criteria.userId(), criteria.exerciseId(), criteria.variantKey(), criteria.unit(),
                selectedAt, effective, excluded);
    }

    private static List<ExclusionReason> reasons(SelectionCriteria criteria, RawSetFact fact) {
        List<ExclusionReason> reasons = new ArrayList<>();
        if (!fact.userId().equals(criteria.userId())) reasons.add(ExclusionReason.USER_MISMATCH);
        if (!fact.exerciseId().equals(criteria.exerciseId())) reasons.add(ExclusionReason.EXERCISE_MISMATCH);
        if (fact.kind() == SetKind.WARMUP) reasons.add(ExclusionReason.WARMUP_SET);
        if (fact.kind() == SetKind.EXTRA) reasons.add(ExclusionReason.EXTRA_SET);
        if (fact.sessionOutcome() != SessionOutcome.COMPLETED) reasons.add(ExclusionReason.INCOMPLETE_SESSION);
        if (fact.status() != FactStatus.COMPLETED) reasons.add(ExclusionReason.INCOMPLETE_SET);
        if (!fact.variantKey().equals(criteria.variantKey())) reasons.add(ExclusionReason.VARIANT_CHANGED);
        if (!fact.unit().equals(criteria.unit())) reasons.add(ExclusionReason.UNIT_CHANGED);
        if (fact.weight() == null) reasons.add(ExclusionReason.MISSING_WEIGHT);
        if (fact.anomalous()) reasons.add(ExclusionReason.ANOMALOUS_INPUT);
        if (!fact.currentRevision()) reasons.add(ExclusionReason.SUPERSEDED_REVISION);
        return List.copyOf(reasons);
    }

    public record SelectionCriteria(UUID userId, UUID exerciseId, String variantKey, String unit) {
        public SelectionCriteria {
            Objects.requireNonNull(userId, "user id must not be null");
            Objects.requireNonNull(exerciseId, "exercise id must not be null");
            variantKey = required(variantKey, "variant key");
            if (!"KG".equals(unit)) throw new IllegalArgumentException("P0 progression selection only supports KG");
        }
    }

    /** Read projection; nullable weight is retained so malformed legacy facts can be excluded with evidence. */
    public record RawSetFact(
            UUID factId,
            UUID sessionId,
            UUID userId,
            UUID exerciseId,
            String variantKey,
            String unit,
            SetKind kind,
            int setOrder,
            SessionOutcome sessionOutcome,
            FactStatus status,
            BigDecimal weight,
            int reps,
            Optional<Integer> remainingReps,
            boolean anomalous,
            boolean currentRevision,
            Instant completedAt,
            long serverRevision,
            String payloadDigest) {
        public RawSetFact {
            Objects.requireNonNull(factId, "fact id must not be null");
            Objects.requireNonNull(sessionId, "session id must not be null");
            Objects.requireNonNull(userId, "user id must not be null");
            Objects.requireNonNull(exerciseId, "exercise id must not be null");
            variantKey = required(variantKey, "variant key");
            unit = required(unit, "unit");
            Objects.requireNonNull(kind, "set kind must not be null");
            Objects.requireNonNull(sessionOutcome, "session outcome must not be null");
            Objects.requireNonNull(status, "fact status must not be null");
            if (setOrder < 1 || reps < 0 || serverRevision < 0) {
                throw new IllegalArgumentException("raw set sequence and values must not be negative");
            }
            if (weight != null && weight.signum() < 0) throw new IllegalArgumentException("weight must not be negative");
            remainingReps = Objects.requireNonNull(remainingReps, "remaining reps must not be null");
            if (remainingReps.filter(value -> value < 0).isPresent()) {
                throw new IllegalArgumentException("remaining reps must not be negative");
            }
            Objects.requireNonNull(completedAt, "completion time must not be null");
            if (payloadDigest == null || !payloadDigest.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("payload digest must be a SHA-256 hex value");
            }
        }
    }

    public enum SetKind { WARMUP, WORK, EXTRA }
    public enum SessionOutcome { COMPLETED, ABORTED, ACTIVE }
    public enum FactStatus { COMPLETED, FAILED, SKIPPED }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
