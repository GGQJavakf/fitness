package com.aifitness.assistant.rules.domain;

public sealed interface RuleEvaluator<I extends RuleEvaluationInput, R extends RuleEvaluationResult>
        permits RuleEvaluator.Plan, RuleEvaluator.Progression {

    R evaluate(I input);

    @FunctionalInterface
    non-sealed interface Plan
            extends RuleEvaluator<RuleEvaluationInput.PlanValidation, RuleEvaluationResult.PlanValidation> {
    }

    @FunctionalInterface
    non-sealed interface Progression
            extends RuleEvaluator<RuleEvaluationInput.Progression, RuleEvaluationResult.Progression> {
    }
}
