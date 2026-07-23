package com.aifitness.assistant.rules.domain;

import java.util.Objects;

public sealed interface RuleEvaluationInput
        permits RuleEvaluationInput.PlanValidation, RuleEvaluationInput.Progression {

    String ruleVersion();

    record PlanValidation(
            String ruleVersion,
            WeightUnit unit,
            int sessionsPerWeek,
            int exerciseCount,
            int workSets,
            int repMin,
            int repMax,
            int restSeconds,
            WeightStatus weightStatus) implements RuleEvaluationInput {

        public PlanValidation {
            ruleVersion = requireText(ruleVersion, "ruleVersion");
            unit = Objects.requireNonNull(unit, "unit must not be null");
            weightStatus = Objects.requireNonNull(weightStatus, "weightStatus must not be null");
        }
    }

    record Progression(
            String ruleVersion,
            WeightUnit unit,
            boolean historySufficient,
            boolean painOrSafetyFlag,
            boolean anomalousInput,
            boolean conflictingInput,
            boolean longTrainingGap,
            boolean variantChanged,
            boolean bodyweightRequiresConfirmation,
            int consecutiveBelowMin,
            boolean multipleFailedSets,
            boolean allSetsAtMax,
            int consecutiveAllAtMax,
            boolean oneSessionBelowMin,
            boolean weightUserLocked,
            Integer rir) implements RuleEvaluationInput {

        public Progression {
            ruleVersion = requireText(ruleVersion, "ruleVersion");
            unit = Objects.requireNonNull(unit, "unit must not be null");
            if (consecutiveBelowMin < 0 || consecutiveAllAtMax < 0) {
                throw new IllegalArgumentException("progression counters must not be negative");
            }
            if (rir != null && (rir < 0 || rir > 3)) {
                throw new IllegalArgumentException("rir must be between 0 and 3");
            }
        }
    }

    enum WeightUnit {
        KG,
        LB
    }

    enum WeightStatus {
        KNOWN,
        NEEDS_CALIBRATION,
        BODYWEIGHT
    }

    private static String requireText(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value;
    }
}
