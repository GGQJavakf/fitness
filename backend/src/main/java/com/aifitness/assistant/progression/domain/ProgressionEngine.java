package com.aifitness.assistant.progression.domain;

import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Pure double-progression rule engine. All numeric policy values are versioned inputs. */
public final class ProgressionEngine {

    public ProgressionDecision evaluate(
            RuleEvaluationInput.Progression input,
            ProgressionDecision.Prescription current,
            EnginePolicy policy,
            EquipmentRoundingPolicy equipment) {
        Objects.requireNonNull(input, "progression signals must not be null");
        Objects.requireNonNull(current, "current prescription must not be null");
        Objects.requireNonNull(policy, "engine policy must not be null");
        Objects.requireNonNull(equipment, "equipment policy must not be null");

        if (input.painOrSafetyFlag()) return review(current, policy, ProgressionDecision.ReasonCode.PAIN_OR_SAFETY_FLAG);
        if (input.anomalousInput()) return review(current, policy, ProgressionDecision.ReasonCode.ANOMALOUS_INPUT);
        if (input.conflictingInput()) return review(current, policy, ProgressionDecision.ReasonCode.CONFLICTING_INPUT);
        if (!input.historySufficient()) return review(current, policy, ProgressionDecision.ReasonCode.INSUFFICIENT_HISTORY);
        if (input.longTrainingGap()) return review(current, policy, ProgressionDecision.ReasonCode.LONG_TRAINING_GAP);
        if (input.variantChanged()) return review(current, policy, ProgressionDecision.ReasonCode.VARIANT_CHANGED);
        if (input.unit() != RuleEvaluationInput.WeightUnit.KG) {
            return review(current, policy, ProgressionDecision.ReasonCode.UNIT_CHANGED);
        }
        if (input.bodyweightRequiresConfirmation()) {
            return input.allSetsAtMax() && input.consecutiveAllAtMax() >= 2
                    ? review(current, policy, ProgressionDecision.ReasonCode.BODYWEIGHT_REQUIRES_CONFIRMATION)
                    : keep(current, policy, ProgressionDecision.ReasonCode.WITHIN_TARGET_RANGE);
        }
        if (input.consecutiveBelowMin() >= 2) {
            return reduce(current, policy, equipment, ProgressionDecision.ReasonCode.CONSECUTIVE_BELOW_MIN);
        }
        if (input.multipleFailedSets()) {
            return reduce(current, policy, equipment, ProgressionDecision.ReasonCode.MULTIPLE_FAILED_SETS);
        }
        if (input.allSetsAtMax() && input.rir() != null && input.rir() == 0) {
            return keep(current, policy, ProgressionDecision.ReasonCode.RIR_ZERO_AT_MAX);
        }
        ProgressionDecision.ReasonCode increaseReason = null;
        if (input.allSetsAtMax() && input.rir() != null && input.rir() >= 1 && input.rir() <= 3) {
            increaseReason = ProgressionDecision.ReasonCode.ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR;
        } else if (input.allSetsAtMax() && input.rir() == null && input.consecutiveAllAtMax() >= 2) {
            increaseReason = ProgressionDecision.ReasonCode.ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR;
        }
        if (increaseReason != null && input.weightUserLocked()) {
            return new ProgressionDecision(
                    ProgressionDecision.Decision.KEEP, ProgressionDecision.ReasonCode.WEIGHT_USER_LOCKED,
                    ProgressionDecision.Application.SUGGEST_ONLY, current, current,
                    Optional.empty(), Optional.empty(), Optional.empty(), List.of(), policy.algorithmVersion());
        }
        if (increaseReason != null) return increase(current, policy, equipment, increaseReason);
        return keep(current, policy, input.allSetsAtMax()
                ? ProgressionDecision.ReasonCode.PARTIAL_AT_MAX
                : ProgressionDecision.ReasonCode.WITHIN_TARGET_RANGE);
    }

    private static ProgressionDecision review(
            ProgressionDecision.Prescription current, EnginePolicy policy, ProgressionDecision.ReasonCode reason) {
        return unchanged(ProgressionDecision.Decision.REVIEW, ProgressionDecision.Application.REVIEW_REQUIRED,
                current, policy, reason);
    }

    private static ProgressionDecision keep(
            ProgressionDecision.Prescription current, EnginePolicy policy, ProgressionDecision.ReasonCode reason) {
        return unchanged(ProgressionDecision.Decision.KEEP, ProgressionDecision.Application.NO_CHANGE,
                current, policy, reason);
    }

    private static ProgressionDecision unchanged(
            ProgressionDecision.Decision decision, ProgressionDecision.Application application,
            ProgressionDecision.Prescription current, EnginePolicy policy, ProgressionDecision.ReasonCode reason) {
        return new ProgressionDecision(decision, reason, application, current, current,
                Optional.empty(), Optional.empty(), Optional.empty(), List.of(), policy.algorithmVersion());
    }

    private static ProgressionDecision increase(
            ProgressionDecision.Prescription current, EnginePolicy policy, EquipmentRoundingPolicy equipment,
            ProgressionDecision.ReasonCode reason) {
        BigDecimal recommended = equipment.increaseOneStep(current.weightKg());
        return changed(ProgressionDecision.Decision.INCREASE, current, current.withWeight(recommended),
                recommended, recommended, EquipmentRoundingPolicy.INCREASE_RULE, policy, equipment, reason);
    }

    private static ProgressionDecision reduce(
            ProgressionDecision.Prescription current, EnginePolicy policy, EquipmentRoundingPolicy equipment,
            ProgressionDecision.ReasonCode reason) {
        BigDecimal raw = current.weightKg().multiply(BigDecimal.ONE.subtract(policy.reductionRate()));
        BigDecimal rounded = equipment.roundReduction(current.weightKg(), raw);
        return changed(ProgressionDecision.Decision.REDUCE, current, current.withWeight(rounded), raw, rounded,
                EquipmentRoundingPolicy.REDUCTION_RULE, policy, equipment, reason);
    }

    private static ProgressionDecision changed(
            ProgressionDecision.Decision decision, ProgressionDecision.Prescription current,
            ProgressionDecision.Prescription recommended, BigDecimal raw, BigDecimal rounded, String roundingRule,
            EnginePolicy policy, EquipmentRoundingPolicy equipment, ProgressionDecision.ReasonCode reason) {
        return new ProgressionDecision(
                decision, reason, ProgressionDecision.Application.RECOMMENDATION_PENDING, current, recommended,
                Optional.of(raw), Optional.of(rounded), Optional.of(roundingRule), equipment.allowedSteps(),
                policy.algorithmVersion());
    }

    public record EnginePolicy(String algorithmVersion, BigDecimal reductionRate) {
        public EnginePolicy {
            if (algorithmVersion == null || algorithmVersion.isBlank()) {
                throw new IllegalArgumentException("algorithm version must not be blank");
            }
            Objects.requireNonNull(reductionRate, "reduction rate must not be null");
            if (reductionRate.compareTo(BigDecimal.ZERO) <= 0 || reductionRate.compareTo(BigDecimal.ONE) >= 0) {
                throw new IllegalArgumentException("reduction rate must be between zero and one");
            }
            reductionRate = reductionRate.stripTrailingZeros();
        }
    }
}
