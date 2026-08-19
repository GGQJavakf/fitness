package com.aifitness.assistant.progression.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.List;
import java.util.UUID;

/** Immutable rule evidence with a small user-controlled lifecycle. */
public record ProgressionRecommendation(
        UUID id,
        UUID userId,
        UUID exerciseId,
        String exerciseCode,
        UUID sourceSessionId,
        ProgressionDecision.Decision decision,
        ProgressionDecision.Prescription currentPrescription,
        ProgressionDecision.Prescription recommendedPrescription,
        String reasonCode,
        String inputSnapshotJson,
        String algorithmVersion,
        Optional<RoundingEvidence> roundingEvidence,
        Status status,
        Optional<BigDecimal> acceptedWeightKg,
        Optional<UUID> appliedPlanId,
        Optional<UUID> appliedPlanVersionId,
        Optional<String> dismissalReason,
        Instant createdAt) {

    public ProgressionRecommendation {
        Objects.requireNonNull(id, "recommendation id must not be null");
        Objects.requireNonNull(userId, "user id must not be null");
        Objects.requireNonNull(exerciseId, "exercise id must not be null");
        exerciseCode = required(exerciseCode, "exercise code");
        Objects.requireNonNull(sourceSessionId, "source session id must not be null");
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(currentPrescription, "current prescription must not be null");
        Objects.requireNonNull(recommendedPrescription, "recommended prescription must not be null");
        reasonCode = required(reasonCode, "reason code");
        inputSnapshotJson = required(inputSnapshotJson, "input snapshot");
        algorithmVersion = required(algorithmVersion, "algorithm version");
        roundingEvidence = safe(roundingEvidence, "rounding evidence");
        Objects.requireNonNull(status, "status must not be null");
        acceptedWeightKg = normalizedWeight(acceptedWeightKg);
        appliedPlanId = safe(appliedPlanId, "applied plan id");
        appliedPlanVersionId = safe(appliedPlanVersionId, "applied plan version id");
        dismissalReason = safe(dismissalReason, "dismissal reason").map(value -> required(value, "dismissal reason"));
        Objects.requireNonNull(createdAt, "created at must not be null");
        validateState(status, acceptedWeightKg, appliedPlanId, appliedPlanVersionId, dismissalReason);
        boolean changesWeight = decision == ProgressionDecision.Decision.INCREASE
                || decision == ProgressionDecision.Decision.REDUCE;
        if (changesWeight != roundingEvidence.isPresent()) {
            throw new IllegalArgumentException("weight-changing recommendation requires rounding evidence");
        }
    }

    public ProgressionRecommendation apply(
            BigDecimal acceptedWeight, UUID planId, UUID planVersionId) {
        requirePending();
        BigDecimal normalized = validateAcceptedWeight(acceptedWeight);
        Status next = normalized.compareTo(recommendedPrescription.weightKg()) == 0
                ? Status.APPLIED : Status.MODIFIED;
        return new ProgressionRecommendation(
                id, userId, exerciseId, exerciseCode, sourceSessionId, decision,
                currentPrescription, recommendedPrescription, reasonCode, inputSnapshotJson, algorithmVersion,
                roundingEvidence,
                next, Optional.of(normalized), Optional.of(planId), Optional.of(planVersionId), Optional.empty(),
                createdAt);
    }

    public BigDecimal validateAcceptedWeight(BigDecimal acceptedWeight) {
        requirePending();
        if (decision != ProgressionDecision.Decision.INCREASE
                && decision != ProgressionDecision.Decision.REDUCE) {
            throw new IllegalArgumentException("recommendation does not contain an applicable weight change");
        }
        BigDecimal normalized = Objects.requireNonNull(acceptedWeight, "accepted weight must not be null")
                .stripTrailingZeros();
        if (!roundingEvidence.orElseThrow().allows(currentPrescription.weightKg(), normalized)) {
            throw new IllegalArgumentException("accepted weight does not match an available equipment level");
        }
        return normalized;
    }

    public ProgressionRecommendation dismiss(String reason) {
        requirePending();
        return new ProgressionRecommendation(
                id, userId, exerciseId, exerciseCode, sourceSessionId, decision,
                currentPrescription, recommendedPrescription, reasonCode, inputSnapshotJson, algorithmVersion,
                roundingEvidence,
                Status.DISMISSED, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of(required(reason, "dismissal reason")), createdAt);
    }

    private void requirePending() {
        if (status != Status.PENDING) {
            throw new IllegalStateException("recommendation is already decided");
        }
    }

    public enum Status { PENDING, APPLIED, MODIFIED, DISMISSED }

    public record RoundingEvidence(
            BigDecimal rawRecommendedWeight,
            BigDecimal roundedWeight,
            String roundingRule,
            List<BigDecimal> availableEquipmentSteps) {
        public RoundingEvidence {
            Objects.requireNonNull(rawRecommendedWeight, "raw recommended weight must not be null");
            Objects.requireNonNull(roundedWeight, "rounded weight must not be null");
            roundingRule = required(roundingRule, "rounding rule");
            availableEquipmentSteps = List.copyOf(Objects.requireNonNull(
                    availableEquipmentSteps, "available equipment steps must not be null"));
            if (rawRecommendedWeight.signum() < 0 || roundedWeight.signum() < 0
                    || availableEquipmentSteps.isEmpty()
                    || availableEquipmentSteps.stream().anyMatch(step -> step == null || step.signum() <= 0)) {
                throw new IllegalArgumentException("rounding evidence values are invalid");
            }
            rawRecommendedWeight = rawRecommendedWeight.stripTrailingZeros();
            roundedWeight = roundedWeight.stripTrailingZeros();
            availableEquipmentSteps = availableEquipmentSteps.stream()
                    .map(BigDecimal::stripTrailingZeros).sorted().toList();
        }

        boolean allows(BigDecimal currentWeight, BigDecimal acceptedWeight) {
            if (acceptedWeight.signum() < 0) return false;
            return availableEquipmentSteps.stream().anyMatch(level -> level.compareTo(acceptedWeight) == 0);
        }
    }

    private static void validateState(
            Status status,
            Optional<BigDecimal> acceptedWeight,
            Optional<UUID> planId,
            Optional<UUID> planVersionId,
            Optional<String> dismissalReason) {
        boolean applied = status == Status.APPLIED || status == Status.MODIFIED;
        if (applied != acceptedWeight.isPresent()
                || applied != planId.isPresent()
                || applied != planVersionId.isPresent()) {
            throw new IllegalArgumentException("applied recommendation metadata is inconsistent");
        }
        if ((status == Status.DISMISSED) != dismissalReason.isPresent()) {
            throw new IllegalArgumentException("dismissal metadata is inconsistent");
        }
    }

    private static Optional<BigDecimal> normalizedWeight(Optional<BigDecimal> value) {
        Optional<BigDecimal> present = safe(value, "accepted weight").map(BigDecimal::stripTrailingZeros);
        if (present.filter(weight -> weight.signum() < 0).isPresent()) {
            throw new IllegalArgumentException("accepted weight must not be negative");
        }
        return present;
    }

    private static <T> Optional<T> safe(Optional<T> value, String name) {
        return Objects.requireNonNull(value, name + " must not be null");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
