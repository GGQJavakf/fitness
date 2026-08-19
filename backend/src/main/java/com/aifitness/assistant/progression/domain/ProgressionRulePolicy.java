package com.aifitness.assistant.progression.domain;

/** Versioned non-medical product thresholds used to derive progression signals. */
public record ProgressionRulePolicy(
        String ruleConfigVersion,
        int longTrainingGapDays,
        int multipleFailedSetsThreshold) {

    public ProgressionRulePolicy {
        if (ruleConfigVersion == null || ruleConfigVersion.isBlank()) {
            throw new IllegalArgumentException("rule config version must not be blank");
        }
        if (longTrainingGapDays < 1 || multipleFailedSetsThreshold < 2) {
            throw new IllegalArgumentException("progression thresholds are invalid");
        }
    }

    public static ProgressionRulePolicy defaults() {
        return new ProgressionRulePolicy("1.6.0", 21, 2);
    }
}
