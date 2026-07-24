package com.aifitness.assistant.progression.domain;

import java.math.BigDecimal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Complete deterministic decision evidence; explanation text is intentionally outside this type. */
public record ProgressionDecision(
        Decision decision,
        ReasonCode reasonCode,
        Application application,
        Prescription currentPrescription,
        Prescription recommendedPrescription,
        Optional<BigDecimal> rawRecommendedWeight,
        Optional<BigDecimal> roundedWeight,
        Optional<String> roundingRule,
        List<BigDecimal> availableEquipmentSteps,
        String algorithmVersion) {

    private static final Set<ReasonCode> REVIEW_REASONS = EnumSet.of(
            ReasonCode.PAIN_OR_SAFETY_FLAG,
            ReasonCode.ANOMALOUS_INPUT,
            ReasonCode.CONFLICTING_INPUT,
            ReasonCode.INSUFFICIENT_HISTORY,
            ReasonCode.LONG_TRAINING_GAP,
            ReasonCode.VARIANT_CHANGED,
            ReasonCode.UNIT_CHANGED,
            ReasonCode.BODYWEIGHT_REQUIRES_CONFIRMATION);

    public ProgressionDecision {
        Objects.requireNonNull(decision, "decision must not be null");
        Objects.requireNonNull(reasonCode, "reason code must not be null");
        Objects.requireNonNull(application, "application must not be null");
        Objects.requireNonNull(currentPrescription, "current prescription must not be null");
        Objects.requireNonNull(recommendedPrescription, "recommended prescription must not be null");
        rawRecommendedWeight = normalized(rawRecommendedWeight, "raw recommended weight");
        roundedWeight = normalized(roundedWeight, "rounded weight");
        roundingRule = Objects.requireNonNull(roundingRule, "rounding rule must not be null");
        availableEquipmentSteps = normalizedSteps(availableEquipmentSteps);
        algorithmVersion = required(algorithmVersion, "algorithm version");
        validateCombination(decision, reasonCode, application);
        boolean changesWeight = decision == Decision.INCREASE || decision == Decision.REDUCE;
        if (changesWeight != rawRecommendedWeight.isPresent()
                || changesWeight != roundedWeight.isPresent()
                || changesWeight != roundingRule.isPresent()
                || changesWeight != !availableEquipmentSteps.isEmpty()) {
            throw new IllegalArgumentException("weight-changing decisions require complete rounding evidence");
        }
        if (changesWeight && recommendedPrescription.weightKg().compareTo(roundedWeight.orElseThrow()) != 0) {
            throw new IllegalArgumentException("recommended prescription must use rounded weight");
        }
        if (!changesWeight && !recommendedPrescription.equals(currentPrescription)) {
            throw new IllegalArgumentException("non-changing decisions must preserve the current prescription");
        }
    }

    public record Prescription(BigDecimal weightKg, int repMin, int repMax) {
        public Prescription {
            Objects.requireNonNull(weightKg, "weight must not be null");
            if (weightKg.signum() < 0 || repMin < 1 || repMax < repMin) {
                throw new IllegalArgumentException("prescription values are invalid");
            }
            weightKg = weightKg.stripTrailingZeros();
        }

        public Prescription withWeight(BigDecimal weight) {
            return new Prescription(weight, repMin, repMax);
        }
    }

    public enum Decision { INCREASE, KEEP, REDUCE, REVIEW }
    public enum Application { RECOMMENDATION_PENDING, NO_CHANGE, SUGGEST_ONLY, REVIEW_REQUIRED }
    public enum ReasonCode {
        PAIN_OR_SAFETY_FLAG,
        ANOMALOUS_INPUT,
        CONFLICTING_INPUT,
        INSUFFICIENT_HISTORY,
        LONG_TRAINING_GAP,
        VARIANT_CHANGED,
        UNIT_CHANGED,
        BODYWEIGHT_REQUIRES_CONFIRMATION,
        CONSECUTIVE_BELOW_MIN,
        MULTIPLE_FAILED_SETS,
        ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR,
        ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR,
        RIR_ZERO_AT_MAX,
        WITHIN_TARGET_RANGE,
        PARTIAL_AT_MAX,
        WEIGHT_USER_LOCKED
    }

    private static Optional<BigDecimal> normalized(Optional<BigDecimal> value, String name) {
        Optional<BigDecimal> present = Objects.requireNonNull(value, name + " must not be null");
        if (present.filter(weight -> weight.signum() < 0).isPresent()) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        return present.map(BigDecimal::stripTrailingZeros);
    }

    private static List<BigDecimal> normalizedSteps(List<BigDecimal> steps) {
        Objects.requireNonNull(steps, "available equipment steps must not be null");
        if (steps.stream().anyMatch(step -> step == null || step.signum() <= 0)) {
            throw new IllegalArgumentException("available equipment steps must be positive");
        }
        return steps.stream().map(BigDecimal::stripTrailingZeros).toList();
    }

    private static void validateCombination(Decision decision, ReasonCode reason, Application application) {
        boolean valid = switch (decision) {
            case REVIEW -> application == Application.REVIEW_REQUIRED && REVIEW_REASONS.contains(reason);
            case INCREASE -> application == Application.RECOMMENDATION_PENDING
                    && (reason == ReasonCode.ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR
                    || reason == ReasonCode.ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR);
            case REDUCE -> application == Application.RECOMMENDATION_PENDING
                    && (reason == ReasonCode.CONSECUTIVE_BELOW_MIN || reason == ReasonCode.MULTIPLE_FAILED_SETS);
            case KEEP -> (application == Application.NO_CHANGE
                    && (reason == ReasonCode.RIR_ZERO_AT_MAX || reason == ReasonCode.WITHIN_TARGET_RANGE
                    || reason == ReasonCode.PARTIAL_AT_MAX))
                    || (application == Application.SUGGEST_ONLY && reason == ReasonCode.WEIGHT_USER_LOCKED);
        };
        if (!valid) throw new IllegalArgumentException("decision, reason and application are inconsistent");
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
