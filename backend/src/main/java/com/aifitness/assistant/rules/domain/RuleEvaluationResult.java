package com.aifitness.assistant.rules.domain;

import java.util.List;
import java.util.Objects;
import java.util.Set;

public sealed interface RuleEvaluationResult
        permits RuleEvaluationResult.PlanValidation, RuleEvaluationResult.Progression {

    String ruleVersion();

    record PlanValidation(
            String ruleVersion,
            PlanOutcome outcome,
            PlanApplication application,
            List<PlanReasonCode> reasonCodes) implements RuleEvaluationResult {

        public PlanValidation {
            ruleVersion = requireText(ruleVersion);
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            application = Objects.requireNonNull(application, "application must not be null");
            reasonCodes = requireReasons(reasonCodes);
            validatePlanCombination(outcome, application, reasonCodes);
        }
    }

    record Progression(
            String ruleVersion,
            ProgressionOutcome outcome,
            Application application,
            List<ProgressionReasonCode> reasonCodes) implements RuleEvaluationResult {

        public Progression {
            ruleVersion = requireText(ruleVersion);
            outcome = Objects.requireNonNull(outcome, "outcome must not be null");
            application = Objects.requireNonNull(application, "application must not be null");
            reasonCodes = requireReasons(reasonCodes);
            validateProgressionCombination(outcome, application, reasonCodes);
        }
    }

    enum PlanOutcome {
        VALID,
        WARNING,
        ERROR
    }

    enum PlanApplication {
        ACCEPTED,
        CALIBRATION_REQUIRED,
        REJECTED
    }

    enum ProgressionOutcome {
        INCREASE,
        KEEP,
        REDUCE,
        REVIEW
    }

    enum Application {
        RECOMMENDATION_PENDING,
        NO_CHANGE,
        SUGGEST_ONLY,
        REVIEW_REQUIRED
    }

    enum PlanReasonCode {
        PLAN_WITHIN_CONFIGURED_LIMITS,
        INITIAL_WEIGHT_NEEDS_CALIBRATION,
        P0_UNIT_NOT_SUPPORTED,
        SESSION_FREQUENCY_OUT_OF_RANGE,
        EXERCISE_COUNT_OUT_OF_RANGE,
        WORK_SETS_OUT_OF_RANGE,
        REP_RANGE_OUT_OF_RANGE,
        REST_OUT_OF_RANGE
    }

    enum ProgressionReasonCode {
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

    private static String requireText(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ruleVersion must not be blank");
        }
        return value;
    }

    private static <T> List<T> requireReasons(List<T> reasonCodes) {
        Objects.requireNonNull(reasonCodes, "reasonCodes must not be null");
        if (reasonCodes.isEmpty() || reasonCodes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("reasonCodes must contain non-null values");
        }
        return List.copyOf(reasonCodes);
    }

    private static void validatePlanCombination(
            PlanOutcome outcome, PlanApplication application, List<PlanReasonCode> reasons) {
        boolean valid = switch (outcome) {
            case VALID -> application == PlanApplication.ACCEPTED
                    && reasons.stream().allMatch(reason -> reason == PlanReasonCode.PLAN_WITHIN_CONFIGURED_LIMITS);
            case WARNING -> application == PlanApplication.CALIBRATION_REQUIRED
                    && reasons.stream().allMatch(reason -> reason == PlanReasonCode.INITIAL_WEIGHT_NEEDS_CALIBRATION);
            case ERROR -> application == PlanApplication.REJECTED
                    && reasons.stream().allMatch(reason -> reason != PlanReasonCode.PLAN_WITHIN_CONFIGURED_LIMITS
                    && reason != PlanReasonCode.INITIAL_WEIGHT_NEEDS_CALIBRATION);
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid plan outcome, application and reason combination");
        }
    }

    private static void validateProgressionCombination(
            ProgressionOutcome outcome, Application application, List<ProgressionReasonCode> reasons) {
        Set<ProgressionReasonCode> reviewReasons = Set.of(
                ProgressionReasonCode.PAIN_OR_SAFETY_FLAG, ProgressionReasonCode.ANOMALOUS_INPUT,
                ProgressionReasonCode.CONFLICTING_INPUT, ProgressionReasonCode.INSUFFICIENT_HISTORY,
                ProgressionReasonCode.LONG_TRAINING_GAP, ProgressionReasonCode.VARIANT_CHANGED,
                ProgressionReasonCode.UNIT_CHANGED, ProgressionReasonCode.BODYWEIGHT_REQUIRES_CONFIRMATION);
        Set<ProgressionReasonCode> increaseReasons = Set.of(
                ProgressionReasonCode.ALL_SETS_AT_MAX_WITH_ACCEPTABLE_RIR,
                ProgressionReasonCode.ALL_SETS_AT_MAX_TWICE_WITHOUT_RIR);
        Set<ProgressionReasonCode> reduceReasons = Set.of(
                ProgressionReasonCode.CONSECUTIVE_BELOW_MIN, ProgressionReasonCode.MULTIPLE_FAILED_SETS);
        Set<ProgressionReasonCode> keepReasons = Set.of(
                ProgressionReasonCode.RIR_ZERO_AT_MAX, ProgressionReasonCode.WITHIN_TARGET_RANGE,
                ProgressionReasonCode.PARTIAL_AT_MAX);
        boolean valid = switch (outcome) {
            case REVIEW -> application == Application.REVIEW_REQUIRED && reviewReasons.containsAll(reasons);
            case INCREASE -> application == Application.RECOMMENDATION_PENDING && increaseReasons.containsAll(reasons);
            case REDUCE -> application == Application.RECOMMENDATION_PENDING && reduceReasons.containsAll(reasons);
            case KEEP -> application == Application.NO_CHANGE && keepReasons.containsAll(reasons)
                    || application == Application.SUGGEST_ONLY
                    && reasons.stream().allMatch(reason -> reason == ProgressionReasonCode.WEIGHT_USER_LOCKED);
        };
        if (!valid) {
            throw new IllegalArgumentException("invalid progression outcome, application and reason combination");
        }
    }
}
