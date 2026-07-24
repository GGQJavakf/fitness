package com.aifitness.assistant.progression;

import static org.assertj.core.api.Assertions.assertThat;

import com.aifitness.assistant.progression.domain.EquipmentRoundingPolicy;
import com.aifitness.assistant.progression.domain.ProgressionDecision;
import com.aifitness.assistant.progression.domain.ProgressionEngine;
import com.aifitness.assistant.rules.domain.RuleEvaluationInput;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

class ProgressionPriorityPropertyTest {
    private static final ProgressionEngine ENGINE = new ProgressionEngine();
    private static final ProgressionDecision.Prescription CURRENT =
            new ProgressionDecision.Prescription(new BigDecimal("40"), 8, 12);
    private static final ProgressionEngine.EnginePolicy POLICY =
            new ProgressionEngine.EnginePolicy("double-progression-v1", new BigDecimal("0.05"));
    private static final EquipmentRoundingPolicy EQUIPMENT =
            new EquipmentRoundingPolicy("KG", List.of(new BigDecimal("2.5")));

    @Test
    void eachHigherPriorityReviewSignalOverridesEveryLowerPrioritySignalAndStrongPerformance() {
        List<ReviewSignal> priority = List.of(
                new ReviewSignal(ProgressionDecision.ReasonCode.PAIN_OR_SAFETY_FLAG,
                        input -> copy(input, true, input.anomalousInput(), input.conflictingInput(),
                                input.historySufficient(), input.longTrainingGap(), input.variantChanged(),
                                input.unit(), input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.ANOMALOUS_INPUT,
                        input -> copy(input, input.painOrSafetyFlag(), true, input.conflictingInput(),
                                input.historySufficient(), input.longTrainingGap(), input.variantChanged(),
                                input.unit(), input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.CONFLICTING_INPUT,
                        input -> copy(input, input.painOrSafetyFlag(), input.anomalousInput(), true,
                                input.historySufficient(), input.longTrainingGap(), input.variantChanged(),
                                input.unit(), input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.INSUFFICIENT_HISTORY,
                        input -> copy(input, input.painOrSafetyFlag(), input.anomalousInput(), input.conflictingInput(),
                                false, input.longTrainingGap(), input.variantChanged(), input.unit(),
                                input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.LONG_TRAINING_GAP,
                        input -> copy(input, input.painOrSafetyFlag(), input.anomalousInput(), input.conflictingInput(),
                                input.historySufficient(), true, input.variantChanged(), input.unit(),
                                input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.VARIANT_CHANGED,
                        input -> copy(input, input.painOrSafetyFlag(), input.anomalousInput(), input.conflictingInput(),
                                input.historySufficient(), input.longTrainingGap(), true, input.unit(),
                                input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.UNIT_CHANGED,
                        input -> copy(input, input.painOrSafetyFlag(), input.anomalousInput(), input.conflictingInput(),
                                input.historySufficient(), input.longTrainingGap(), input.variantChanged(),
                                RuleEvaluationInput.WeightUnit.LB, input.bodyweightRequiresConfirmation())),
                new ReviewSignal(ProgressionDecision.ReasonCode.BODYWEIGHT_REQUIRES_CONFIRMATION,
                        input -> copy(input, input.painOrSafetyFlag(), input.anomalousInput(), input.conflictingInput(),
                                input.historySufficient(), input.longTrainingGap(), input.variantChanged(), input.unit(), true)));

        for (int higher = 0; higher < priority.size(); higher++) {
            assertReviewReason(priority.get(higher), strongPerformance(), "performance");
            assertReviewReason(priority.get(higher), reductionAndPerformance(), "reduction");
            for (int lower = higher + 1; lower < priority.size(); lower++) {
                RuleEvaluationInput.Progression input = priority.get(lower).apply().apply(strongPerformance());
                input = priority.get(higher).apply().apply(input);
                ProgressionDecision decision = ENGINE.evaluate(input, CURRENT, POLICY, EQUIPMENT);
                assertThat(decision.reasonCode()).as("priority %s over %s", higher, lower)
                        .isEqualTo(priority.get(higher).reason());
                assertThat(decision.decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
            }
        }

        ProgressionDecision reduction = ENGINE.evaluate(reductionAndPerformance(), CURRENT, POLICY, EQUIPMENT);
        assertThat(reduction.decision()).isEqualTo(ProgressionDecision.Decision.REDUCE);
        assertThat(reduction.reasonCode()).isEqualTo(ProgressionDecision.ReasonCode.CONSECUTIVE_BELOW_MIN);
    }

    @Test
    void theSameInputAndAlgorithmVersionAlwaysProduceTheSameDecision() {
        ProgressionDecision expected = ENGINE.evaluate(strongPerformance(), CURRENT, POLICY, EQUIPMENT);
        for (int iteration = 0; iteration < 100; iteration++) {
            assertThat(ENGINE.evaluate(strongPerformance(), CURRENT, POLICY, EQUIPMENT)).isEqualTo(expected);
        }
    }

    private static RuleEvaluationInput.Progression strongPerformance() {
        return new RuleEvaluationInput.Progression(
                "1.0.0", RuleEvaluationInput.WeightUnit.KG, true, false, false, false, false, false,
                false, 0, false, true, 2, false, false, 2);
    }

    private static RuleEvaluationInput.Progression reductionAndPerformance() {
        RuleEvaluationInput.Progression input = strongPerformance();
        return new RuleEvaluationInput.Progression(
                input.ruleVersion(), input.unit(), input.historySufficient(), input.painOrSafetyFlag(),
                input.anomalousInput(), input.conflictingInput(), input.longTrainingGap(), input.variantChanged(),
                input.bodyweightRequiresConfirmation(), 2, input.multipleFailedSets(), input.allSetsAtMax(),
                input.consecutiveAllAtMax(), input.oneSessionBelowMin(), input.weightUserLocked(), input.rir());
    }

    private static void assertReviewReason(
            ReviewSignal signal, RuleEvaluationInput.Progression base, String lowerPrioritySignal) {
        ProgressionDecision decision = ENGINE.evaluate(signal.apply().apply(base), CURRENT, POLICY, EQUIPMENT);
        assertThat(decision.reasonCode()).as("review over %s", lowerPrioritySignal).isEqualTo(signal.reason());
        assertThat(decision.decision()).isEqualTo(ProgressionDecision.Decision.REVIEW);
    }

    private static RuleEvaluationInput.Progression copy(
            RuleEvaluationInput.Progression input, boolean pain, boolean anomaly, boolean conflict,
            boolean history, boolean gap, boolean variant, RuleEvaluationInput.WeightUnit unit,
            boolean bodyweight) {
        return new RuleEvaluationInput.Progression(
                input.ruleVersion(), unit, history, pain, anomaly, conflict, gap, variant, bodyweight,
                input.consecutiveBelowMin(), input.multipleFailedSets(), input.allSetsAtMax(),
                input.consecutiveAllAtMax(), input.oneSessionBelowMin(), input.weightUserLocked(), input.rir());
    }

    private record ReviewSignal(
            ProgressionDecision.ReasonCode reason, UnaryOperator<RuleEvaluationInput.Progression> apply) {}
}
